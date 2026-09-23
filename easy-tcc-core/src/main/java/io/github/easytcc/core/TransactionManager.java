package io.github.easytcc.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class TransactionManager {
    private final TransactionRepository repository;
    private final BranchInvoker invoker;
    private final int maxRetries;
    private final Map<String, AtomicInteger> locallyActive =
            new ConcurrentHashMap<String, AtomicInteger>();
    private final long executionLeaseMillis;
    private final EasyTccMetrics metrics = new EasyTccMetrics();

    public TransactionManager(
            TransactionRepository repository, BranchInvoker invoker, int maxRetries) {
        this(repository, invoker, maxRetries, 30000L);
    }

    public TransactionManager(
            TransactionRepository repository,
            BranchInvoker invoker,
            int maxRetries,
            long executionLeaseMillis) {
        this.repository = repository;
        this.invoker = invoker;
        this.maxRetries = maxRetries;
        this.executionLeaseMillis = executionLeaseMillis;
    }

    public GlobalTransaction begin(String name, long timeoutMillis) {
        long now = System.currentTimeMillis();
        GlobalTransaction tx =
                new GlobalTransaction(UUID.randomUUID().toString(), name, now, now + timeoutMillis);
        tx.setExecutionLeaseUntil(now + executionLeaseMillis);
        repository.create(tx);
        metrics.transactionStarted();
        audit(tx.getXid(), "BEGIN", name, "system");
        enter(tx.getXid());
        EasyTccContext.bind(tx.getXid());
        return tx;
    }

    public BranchTransaction registerBranch(
            String name, String beanName, String confirm, String cancel, Object[] args) {
        String xid = EasyTccContext.currentXid();
        if (xid == null) return null;
        GlobalTransaction tx = required(xid);
        BranchTransaction branch =
                new BranchTransaction(
                        UUID.randomUUID().toString(), xid, name, beanName, confirm, cancel, args);
        try {
            invoker.validate(branch);
        } catch (Exception e) {
            throw new EasyTccException("Invalid TCC branch " + name, e);
        }
        enter(xid);
        try {
            repository.renewExecutionLease(xid, System.currentTimeMillis() + executionLeaseMillis);
            long expectedVersion = tx.getVersion();
            tx.addBranch(branch);
            save(tx, expectedVersion);
            return branch;
        } catch (RuntimeException e) {
            release(xid);
            throw e;
        }
    }

    public void markTrySucceeded(BranchTransaction branch) {
        if (branch == null) return;
        GlobalTransaction tx = required(branch.getXid());
        long expectedVersion = tx.getVersion();
        findBranch(tx, branch.getBranchId()).setStatus(BranchStatus.TRY_SUCCEEDED);
        tx.touch();
        save(tx, expectedVersion);
    }

    public void markTryFailed(BranchTransaction branch, Throwable error) {
        if (branch == null) return;
        GlobalTransaction tx = required(branch.getXid());
        long expectedVersion = tx.getVersion();
        BranchTransaction stored = findBranch(tx, branch.getBranchId());
        stored.setStatus(BranchStatus.TRY_FAILED);
        stored.setLastError(message(error));
        tx.touch();
        save(tx, expectedVersion);
    }

    public void confirm(String xid) {
        GlobalTransaction tx = required(xid);
        if (tx.getStatus() != GlobalStatus.TRYING
                && tx.getStatus() != GlobalStatus.CONFIRMING
                && tx.getStatus() != GlobalStatus.CONFIRM_FAILED) {
            throw new EasyTccException(
                    "Transaction cannot be confirmed from " + tx.getStatus() + ": " + xid);
        }
        execute(tx, true);
    }

    public void cancel(String xid) {
        GlobalTransaction tx = required(xid);
        if (tx.getStatus() == GlobalStatus.CONFIRMED
                || tx.getStatus() == GlobalStatus.CONFIRMING
                || tx.getStatus() == GlobalStatus.CONFIRM_FAILED) {
            throw new EasyTccException(
                    "Transaction cannot be cancelled from " + tx.getStatus() + ": " + xid);
        }
        execute(tx, false);
    }

    public boolean isLocallyActive(String xid) {
        return locallyActive.containsKey(xid);
    }

    public void release(String xid) {
        AtomicInteger count = locallyActive.get(xid);
        if (count != null && count.decrementAndGet() <= 0 && locallyActive.remove(xid, count))
            repository.releaseExecutionLease(xid);
    }

    public void renewActiveLeases(long now) {
        long until = now + executionLeaseMillis;
        for (String xid : locallyActive.keySet()) repository.renewExecutionLease(xid, until);
    }

    private void enter(String xid) {
        locallyActive.computeIfAbsent(xid, key -> new AtomicInteger()).incrementAndGet();
    }

    public void recover(GlobalTransaction transaction) {
        if (transaction.getRetryCount() >= maxRetries) {
            long expectedVersion = transaction.getVersion();
            transaction.setStatus(GlobalStatus.MANUAL_INTERVENTION);
            transaction.setLastError(
                    "Recovery attempts exhausted after "
                            + transaction.getRetryCount()
                            + " retries");
            save(transaction, expectedVersion);
            metrics.manualIntervention();
            audit(transaction.getXid(), "MANUAL_INTERVENTION", "retries exhausted", "system");
            return;
        }
        long expectedVersion = transaction.getVersion();
        metrics.recoveryAttempt();
        transaction.incrementRetryCount();
        save(transaction, expectedVersion);
        execute(transaction, transaction.getDecision() == TransactionDecision.CONFIRM);
    }

    public GlobalTransaction suspend(String xid, String reason, String operator) {
        GlobalTransaction tx = required(xid);
        if (tx.getStatus() == GlobalStatus.CONFIRMED || tx.getStatus() == GlobalStatus.CANCELLED)
            throw new EasyTccException("Terminal transaction cannot be suspended: " + xid);
        long expected = tx.getVersion();
        tx.setLastError(reason);
        tx.setStatus(GlobalStatus.MANUAL_INTERVENTION);
        save(tx, expected);
        metrics.manualIntervention();
        audit(xid, "SUSPEND", reason, operator);
        return tx;
    }

    public void retryManually(String xid, String operator) {
        GlobalTransaction tx = required(xid);
        if (tx.getStatus() != GlobalStatus.MANUAL_INTERVENTION)
            throw new EasyTccException("Transaction is not awaiting manual intervention: " + xid);
        if (tx.getDecision() == TransactionDecision.UNDECIDED)
            throw new EasyTccException("Transaction has no durable decision: " + xid);
        long expected = tx.getVersion();
        tx.resetRetryCount();
        tx.setStatus(
                tx.getDecision() == TransactionDecision.CONFIRM
                        ? GlobalStatus.CONFIRM_FAILED
                        : GlobalStatus.CANCEL_FAILED);
        save(tx, expected);
        audit(xid, "MANUAL_RETRY", tx.getDecision().name(), operator);
        recover(tx);
    }

    public java.util.Optional<GlobalTransaction> query(String xid) {
        return repository.find(xid);
    }

    public java.util.List<TransactionAuditEvent> auditTrail(String xid, int limit) {
        return repository.findAudit(xid, limit);
    }

    /**
     * 按保留期归档清理已到终态（CONFIRMED/CANCELLED）的历史事务，避免存储无限膨胀。 返回实际删除的 xid 列表，供调度器以结构化日志 + 指标完成"操作可审计"留痕。
     *
     * @param retentionMillis 保留期（毫秒），创建时间早于 {@code now - retentionMillis} 的终态事务才会被清理；&lt;=0 表示不清理
     * @param batchSize 单次清理上限
     * @return 被删除的 xid 列表
     */
    public java.util.List<String> purgeCompleted(long retentionMillis, int batchSize) {
        if (retentionMillis <= 0 || batchSize <= 0) return Collections.emptyList();
        long createdBefore = System.currentTimeMillis() - retentionMillis;
        java.util.List<String> deleted = repository.deleteTerminal(createdBefore, batchSize);
        if (!deleted.isEmpty()) metrics.purged(deleted.size());
        return deleted;
    }

    private void execute(GlobalTransaction tx, boolean confirming) {
        long expectedVersion = tx.getVersion();
        TransactionDecision decision =
                confirming ? TransactionDecision.CONFIRM : TransactionDecision.CANCEL;
        if (tx.getDecision() != TransactionDecision.UNDECIDED && tx.getDecision() != decision)
            throw new EasyTccException("Durable decision conflict for " + tx.getXid());
        if (tx.getDecision() == TransactionDecision.UNDECIDED) tx.setDecision(decision);
        tx.setStatus(confirming ? GlobalStatus.CONFIRMING : GlobalStatus.CANCELLING);
        save(tx, expectedVersion);
        audit(tx.getXid(), confirming ? "CONFIRMING" : "CANCELLING", "", "system");
        List<BranchTransaction> branches = new ArrayList<BranchTransaction>(tx.getBranches());
        if (!confirming) Collections.reverse(branches);
        try {
            for (BranchTransaction branch : branches) {
                expectedVersion = tx.getVersion();
                if (confirming) confirm(branch);
                else cancel(branch);
                tx.touch();
                save(tx, expectedVersion);
            }
            expectedVersion = tx.getVersion();
            tx.setStatus(confirming ? GlobalStatus.CONFIRMED : GlobalStatus.CANCELLED);
            audit(tx.getXid(), confirming ? "CONFIRMED" : "CANCELLED", "", "system");
        } catch (Exception error) {
            metrics.failure();
            expectedVersion = tx.getVersion();
            tx.setStatus(confirming ? GlobalStatus.CONFIRM_FAILED : GlobalStatus.CANCEL_FAILED);
            tx.setNextRetryAt(System.currentTimeMillis() + retryDelay(tx.getRetryCount()));
            save(tx, expectedVersion);
            audit(
                    tx.getXid(),
                    confirming ? "CONFIRM_FAILED" : "CANCEL_FAILED",
                    message(error),
                    "system");
            throw new EasyTccException(
                    "TCC " + (confirming ? "confirm" : "cancel") + " failed for " + tx.getXid(),
                    error);
        }
        save(tx, expectedVersion);
        metrics.transactionCompleted();
    }

    private void confirm(BranchTransaction branch) throws Exception {
        if (branch.getStatus() == BranchStatus.CONFIRMED
                || branch.getStatus() == BranchStatus.CANCELLED) return;
        if (branch.getStatus() != BranchStatus.TRY_SUCCEEDED
                && branch.getStatus() != BranchStatus.CONFIRM_FAILED) return;
        branch.setStatus(BranchStatus.CONFIRMING);
        try {
            invoker.confirm(branch);
            branch.setStatus(BranchStatus.CONFIRMED);
        } catch (Exception e) {
            branch.setStatus(BranchStatus.CONFIRM_FAILED);
            branch.setLastError(message(e));
            throw e;
        }
    }

    private void cancel(BranchTransaction branch) throws Exception {
        if (branch.getStatus() == BranchStatus.CANCELLED
                || branch.getStatus() == BranchStatus.CONFIRMED) return;
        branch.setStatus(BranchStatus.CANCELLING);
        try {
            invoker.cancel(branch);
            branch.setStatus(BranchStatus.CANCELLED);
        } catch (Exception e) {
            branch.setStatus(BranchStatus.CANCEL_FAILED);
            branch.setLastError(message(e));
            throw e;
        }
    }

    private GlobalTransaction required(String xid) {
        return repository
                .find(xid)
                .orElseThrow(() -> new EasyTccException("Transaction not found: " + xid));
    }

    private static BranchTransaction findBranch(GlobalTransaction tx, String branchId) {
        for (BranchTransaction branch : tx.getBranches()) {
            if (branch.getBranchId().equals(branchId)) return branch;
        }
        throw new EasyTccException("Branch not found: " + branchId);
    }

    private long retryDelay(int retryCount) {
        return Math.min(300000L, 1000L << Math.min(retryCount, 8));
    }

    private void save(GlobalTransaction tx, long expectedVersion) {
        if (!repository.compareAndSet(tx, expectedVersion)) {
            throw new EasyTccException("Concurrent transaction update rejected: " + tx.getXid());
        }
    }

    private void audit(String xid, String operation, String detail, String operator) {
        repository.appendAudit(
                new TransactionAuditEvent(
                        xid, System.currentTimeMillis(), operation, detail, operator));
    }

    public EasyTccMetrics getMetrics() {
        return metrics;
    }

    private static String message(Throwable e) {
        return e == null ? null : e.getClass().getName() + ": " + e.getMessage();
    }
}
