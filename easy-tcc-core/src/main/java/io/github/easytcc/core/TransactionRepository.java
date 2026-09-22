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
    default void appendAudit(TransactionAuditEvent event) { }
    default List<TransactionAuditEvent> findAudit(String xid, int limit) { return java.util.Collections.emptyList(); }
    default java.util.Map<GlobalStatus, Long> countByStatus() { return java.util.Collections.emptyMap(); }
    List<GlobalTransaction> findRecoverable(long now, int limit);

    /**
     * 删除已到终态（{@link GlobalStatus#CONFIRMED}/{@link GlobalStatus#CANCELLED}）且创建时间
     * 早于 {@code createdBefore} 的事务，返回被删除的 xid 列表（供清理动作留痕审计）。
     * 用于按保留期归档清理，避免已完成事务在存储中无限膨胀。
     *
     * <p>实现应同时清理关联的屏障与审计数据，保证不残留孤儿记录。清理动作本身的审计由调用方
     * （{@code TransactionManager#purgeCompleted} 与调度器）通过日志和指标落地。
     *
     * @param createdBefore 创建时间早于该值（epoch 毫秒）的终态事务才会被删除
     * @param limit         单次清理的最大事务数
     * @return 实际删除的 xid 列表
     */
    default List<String> deleteTerminal(long createdBefore, int limit) { return java.util.Collections.emptyList(); }
}
