package io.github.easytcc.spring;

import io.github.easytcc.core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.concurrent.*;

public final class RecoveryScheduler implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RecoveryScheduler.class);
    private final TransactionRepository repository;
    private final TransactionManager manager;
    private final ScheduledExecutorService executor;
    private final long intervalMillis;
    private final int batchSize;

    public RecoveryScheduler(TransactionRepository repository, TransactionManager manager, long intervalMillis, int batchSize) {
        this.repository = repository; this.manager = manager; this.intervalMillis = intervalMillis; this.batchSize = batchSize;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "easy-tcc-recovery"); t.setDaemon(true); return t;
        });
    }
    public void start() { executor.scheduleWithFixedDelay(this::scan, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS); }
    void scan() {
        List<GlobalTransaction> transactions = repository.findRecoverable(System.currentTimeMillis(), batchSize);
        for (GlobalTransaction tx : transactions) {
            try { manager.recover(tx); }
            catch (RuntimeException e) { log.warn("easyTcc recovery failed, xid={}", tx.getXid(), e); }
        }
    }
    @Override public void close() { executor.shutdownNow(); }
}
