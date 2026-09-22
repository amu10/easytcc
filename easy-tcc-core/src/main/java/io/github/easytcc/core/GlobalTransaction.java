package io.github.easytcc.core;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GlobalTransaction implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String xid;
    private final String name;
    private final long createdAt;
    private final long deadline;
    private GlobalStatus status;
    private int retryCount;
    private long nextRetryAt;
    private long version;
    private String recoveryOwner;
    private long recoveryLeaseUntil;
    private final List<BranchTransaction> branches = new ArrayList<BranchTransaction>();

    public GlobalTransaction(String xid, String name, long createdAt, long deadline) {
        this.xid = xid; this.name = name; this.createdAt = createdAt; this.deadline = deadline;
        this.status = GlobalStatus.TRYING;
    }
    public String getXid() { return xid; }
    public String getName() { return name; }
    public long getCreatedAt() { return createdAt; }
    public long getDeadline() { return deadline; }
    public GlobalStatus getStatus() { return status; }
    public void setStatus(GlobalStatus status) { this.status = status; this.version++; }
    public int getRetryCount() { return retryCount; }
    public void incrementRetryCount() { this.retryCount++; this.version++; }
    public long getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(long nextRetryAt) { this.nextRetryAt = nextRetryAt; this.version++; }
    public long getVersion() { return version; }
    public String getRecoveryOwner() { return recoveryOwner; }
    public long getRecoveryLeaseUntil() { return recoveryLeaseUntil; }
    public void claimRecovery(String owner, long leaseUntil) { this.recoveryOwner = owner; this.recoveryLeaseUntil = leaseUntil; this.version++; }
    public void clearRecoveryClaim() { this.recoveryOwner = null; this.recoveryLeaseUntil = 0L; this.version++; }
    public void touch() { this.version++; }
    public List<BranchTransaction> getBranches() { return Collections.unmodifiableList(branches); }
    public void addBranch(BranchTransaction branch) { branches.add(branch); version++; }
}
