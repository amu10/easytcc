package io.github.easytcc.core;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository {
    void create(GlobalTransaction transaction);
    Optional<GlobalTransaction> find(String xid);
    void save(GlobalTransaction transaction);
    boolean compareAndSet(GlobalTransaction transaction, long expectedVersion);
    Optional<GlobalTransaction> tryClaimRecovery(String xid, long expectedVersion,
                                                  String owner, long leaseUntil, long now);
    boolean renewExecutionLease(String xid, long leaseUntil);
    void releaseExecutionLease(String xid);
    List<GlobalTransaction> findRecoverable(long now, int limit);
}
