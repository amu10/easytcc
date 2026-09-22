package io.github.easytcc.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class TransactionManager {
    private final TransactionRepository repository;
    private final BranchInvoker invoker;
    private final int maxRetries;
    private final Map<String, AtomicInteger> locallyActive = new ConcurrentHashMap<String, AtomicInteger>();
    private final long executionLeaseMillis;

    public TransactionManager(TransactionRepository repository, BranchInvoker invoker, int maxRetries) {
        this(repository, invoker, maxRetries, 30000L);
    }
    public TransactionManager(TransactionRepository repository, BranchInvoker invoker, int maxRetries, long executionLeaseMillis) {
        this.repository = repository;
        this.invoker = invoker;
        this.maxRetries = maxRetries;
        this.executionLeaseMillis = executionLeaseMillis;
    }

    public GlobalTransaction begin(String name, long timeoutMillis) {
        long now = System.currentTimeMillis();
        GlobalTransaction tx = new GlobalTransaction(UUID.randomUUID().toString(), name, now, now + timeoutMillis);
        tx.setExecutionLeaseUntil(now + executionLeaseMillis);
        repository.create(tx);
        enter(tx.getXid());
        EasyTccContext.bind(tx.getXid());
        return tx;
    }

    public BranchTransaction registerBranch(String name, String beanName, String confirm, String cancel, Object[] args) {
        String xid = EasyTccContext.currentXid();
        if (xid == null) return null;
        GlobalTransaction tx = required(xid);
        BranchTransaction branch = new BranchTransaction(UUID.randomUUID().toString(), xid, name, beanName, confirm, cancel, args);
        try { invoker.validate(branch); }
        catch (Exception e) { throw new EasyTccException("Invalid TCC branch " + name, e); }
        enter(xid);
        try {
            repository.renewExecutionLease(xid, System.currentTimeMillis() + executionLeaseMillis);
            long expectedVersion = tx.getVersion();
            tx.addBranch(branch);
            save(tx, expectedVersion);
            return branch;
        } catch (RuntimeException e) { release(xid); throw e; }
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
        if (tx.getStatus() != GlobalStatus.TRYING && tx.getStatus() != GlobalStatus.CONFIRMING &&
                tx.getStatus() != GlobalStatus.CONFIRM_FAILED) {
            throw new EasyTccException("Transaction cannot be confirmed from " + tx.getStatus() + ": " + xid);
        }
        execute(tx, true);
    }
    public void cancel(String xid) {
        GlobalTransaction tx = required(xid);
        if (tx.getStatus() == GlobalStatus.CONFIRMED || tx.getStatus() == GlobalStatus.CONFIRMING ||
                tx.getStatus() == GlobalStatus.CONFIRM_FAILED) {
            throw new EasyTccException("Transaction cannot be cancelled from " + tx.getStatus() + ": " + xid);
        }
        execute(tx, false);
    }

    public boolean isLocallyActive(String xid) { return locallyActive.containsKey(xid); }
    public void release(String xid) {
        AtomicInteger count = locallyActive.get(xid);
        if (count != null && count.decrementAndGet() <= 0 && locallyActive.remove(xid, count)) repository.releaseExecutionLease(xid);
    }
    public void renewActiveLeases(long now) {
        long until = now + executionLeaseMillis;
        for (String xid : locallyActive.keySet()) repository.renewExecutionLease(xid, until);
    }
    private void enter(String xid) { locallyActive.computeIfAbsent(xid, key -> new AtomicInteger()).incrementAndGet(); }

    public void recover(GlobalTransaction transaction) {
        if (transaction.getRetryCount() >= maxRetries) {
            long expectedVersion = transaction.getVersion();
            transaction.setStatus(GlobalStatus.MANUAL_INTERVENTION);
            save(transaction, expectedVersion);
            return;
        }
        long expectedVersion = transaction.getVersion();
        transaction.incrementRetryCount();
        save(transaction, expectedVersion);
        execute(transaction, transaction.getStatus() == GlobalStatus.CONFIRMING || transaction.getStatus() == GlobalStatus.CONFIRM_FAILED);
    }

    private void execute(GlobalTransaction tx, boolean confirming) {
        long expectedVersion = tx.getVersion();
        tx.setStatus(confirming ? GlobalStatus.CONFIRMING : GlobalStatus.CANCELLING);
        save(tx, expectedVersion);
        List<BranchTransaction> branches = new ArrayList<BranchTransaction>(tx.getBranches());
        if (!confirming) Collections.reverse(branches);
        try {
            for (BranchTransaction branch : branches) {
                expectedVersion = tx.getVersion();
                if (confirming) confirm(branch); else cancel(branch);
                tx.touch();
                save(tx, expectedVersion);
            }
            expectedVersion = tx.getVersion();
            tx.setStatus(confirming ? GlobalStatus.CONFIRMED : GlobalStatus.CANCELLED);
        } catch (Exception error) {
            expectedVersion = tx.getVersion();
            tx.setStatus(confirming ? GlobalStatus.CONFIRM_FAILED : GlobalStatus.CANCEL_FAILED);
            tx.setNextRetryAt(System.currentTimeMillis() + retryDelay(tx.getRetryCount()));
            save(tx, expectedVersion);
            throw new EasyTccException("TCC " + (confirming ? "confirm" : "cancel") + " failed for " + tx.getXid(), error);
        }
        save(tx, expectedVersion);
    }

    private void confirm(BranchTransaction branch) throws Exception {
        if (branch.getStatus() == BranchStatus.CONFIRMED || branch.getStatus() == BranchStatus.CANCELLED) return;
        if (branch.getStatus() != BranchStatus.TRY_SUCCEEDED && branch.getStatus() != BranchStatus.CONFIRM_FAILED) return;
        branch.setStatus(BranchStatus.CONFIRMING);
        try { invoker.confirm(branch); branch.setStatus(BranchStatus.CONFIRMED); }
        catch (Exception e) { branch.setStatus(BranchStatus.CONFIRM_FAILED); branch.setLastError(message(e)); throw e; }
    }

    private void cancel(BranchTransaction branch) throws Exception {
        if (branch.getStatus() == BranchStatus.CANCELLED || branch.getStatus() == BranchStatus.CONFIRMED) return;
        branch.setStatus(BranchStatus.CANCELLING);
        try { invoker.cancel(branch); branch.setStatus(BranchStatus.CANCELLED); }
        catch (Exception e) { branch.setStatus(BranchStatus.CANCEL_FAILED); branch.setLastError(message(e)); throw e; }
    }

    private GlobalTransaction required(String xid) {
        return repository.find(xid).orElseThrow(() -> new EasyTccException("Transaction not found: " + xid));
    }
    private static BranchTransaction findBranch(GlobalTransaction tx, String branchId) {
        for (BranchTransaction branch : tx.getBranches()) {
            if (branch.getBranchId().equals(branchId)) return branch;
        }
        throw new EasyTccException("Branch not found: " + branchId);
    }
    private long retryDelay(int retryCount) { return Math.min(300000L, 1000L << Math.min(retryCount, 8)); }
    private void save(GlobalTransaction tx, long expectedVersion) {
        if (!repository.compareAndSet(tx, expectedVersion)) {
            throw new EasyTccException("Concurrent transaction update rejected: " + tx.getXid());
        }
    }
    private static String message(Throwable e) { return e == null ? null : e.getClass().getName() + ": " + e.getMessage(); }
}
