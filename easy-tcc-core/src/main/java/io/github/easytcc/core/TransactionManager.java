package io.github.easytcc.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class TransactionManager {
    private final TransactionRepository repository;
    private final BranchInvoker invoker;
    private final int maxRetries;

    public TransactionManager(TransactionRepository repository, BranchInvoker invoker, int maxRetries) {
        this.repository = repository;
        this.invoker = invoker;
        this.maxRetries = maxRetries;
    }

    public GlobalTransaction begin(String name, long timeoutMillis) {
        long now = System.currentTimeMillis();
        GlobalTransaction tx = new GlobalTransaction(UUID.randomUUID().toString(), name, now, now + timeoutMillis);
        repository.create(tx);
        EasyTccContext.bind(tx.getXid());
        return tx;
    }

    public BranchTransaction registerBranch(String name, String beanName, String confirm, String cancel, Object[] args) {
        String xid = EasyTccContext.currentXid();
        if (xid == null) return null;
        GlobalTransaction tx = required(xid);
        BranchTransaction branch = new BranchTransaction(UUID.randomUUID().toString(), xid, name, beanName, confirm, cancel, args);
        tx.addBranch(branch);
        repository.save(tx);
        return branch;
    }

    public void markTrySucceeded(BranchTransaction branch) {
        if (branch == null) return;
        GlobalTransaction tx = required(branch.getXid());
        findBranch(tx, branch.getBranchId()).setStatus(BranchStatus.TRY_SUCCEEDED);
        repository.save(tx);
    }

    public void markTryFailed(BranchTransaction branch, Throwable error) {
        if (branch == null) return;
        GlobalTransaction tx = required(branch.getXid());
        BranchTransaction stored = findBranch(tx, branch.getBranchId());
        stored.setStatus(BranchStatus.TRY_FAILED);
        stored.setLastError(message(error));
        repository.save(tx);
    }

    public void confirm(String xid) { execute(required(xid), true); }
    public void cancel(String xid) { execute(required(xid), false); }

    public void recover(GlobalTransaction transaction) {
        if (transaction.getRetryCount() >= maxRetries) {
            transaction.setStatus(GlobalStatus.MANUAL_INTERVENTION);
            repository.save(transaction);
            return;
        }
        transaction.incrementRetryCount();
        execute(transaction, transaction.getStatus() == GlobalStatus.CONFIRMING || transaction.getStatus() == GlobalStatus.CONFIRM_FAILED);
    }

    private void execute(GlobalTransaction tx, boolean confirming) {
        tx.setStatus(confirming ? GlobalStatus.CONFIRMING : GlobalStatus.CANCELLING);
        repository.save(tx);
        List<BranchTransaction> branches = new ArrayList<BranchTransaction>(tx.getBranches());
        if (!confirming) Collections.reverse(branches);
        try {
            for (BranchTransaction branch : branches) {
                if (confirming) confirm(branch); else cancel(branch);
                repository.save(tx);
            }
            tx.setStatus(confirming ? GlobalStatus.CONFIRMED : GlobalStatus.CANCELLED);
        } catch (Exception error) {
            tx.setStatus(confirming ? GlobalStatus.CONFIRM_FAILED : GlobalStatus.CANCEL_FAILED);
            tx.setNextRetryAt(System.currentTimeMillis() + retryDelay(tx.getRetryCount()));
            repository.save(tx);
            throw new EasyTccException("TCC " + (confirming ? "confirm" : "cancel") + " failed for " + tx.getXid(), error);
        }
        repository.save(tx);
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
    private static String message(Throwable e) { return e == null ? null : e.getClass().getName() + ": " + e.getMessage(); }
}
