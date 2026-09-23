package io.github.easytcc.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TransactionManagerTest {
    @AfterEach
    void clear() {
        EasyTccContext.clear();
    }

    @Test
    void confirmsInOrderAndCancelsInReverseOrder() {
        MemoryRepository repository = new MemoryRepository();
        List<String> calls = new ArrayList<String>();
        TransactionManager manager =
                new TransactionManager(repository, new RecordingInvoker(calls), 3);
        GlobalTransaction tx = manager.begin("order", 1000);
        BranchTransaction one =
                manager.registerBranch("one", "bean", "confirm", "cancel", new Object[0]);
        manager.markTrySucceeded(one);
        BranchTransaction two =
                manager.registerBranch("two", "bean", "confirm", "cancel", new Object[0]);
        manager.markTrySucceeded(two);
        manager.confirm(tx.getXid());
        assertEquals(Arrays.asList("confirm:one", "confirm:two"), calls);
        assertEquals(
                GlobalStatus.CONFIRMED,
                repository.find(tx.getXid()).orElseThrow(AssertionError::new).getStatus());

        calls.clear();
        GlobalTransaction failed = manager.begin("failed-order", 1000);
        one = manager.registerBranch("one", "bean", "confirm", "cancel", new Object[0]);
        manager.markTrySucceeded(one);
        two = manager.registerBranch("two", "bean", "confirm", "cancel", new Object[0]);
        manager.markTryFailed(two, new RuntimeException("boom"));
        manager.cancel(failed.getXid());
        assertEquals(Arrays.asList("cancel:two", "cancel:one"), calls);
        assertEquals(
                GlobalStatus.CANCELLED,
                repository.find(failed.getXid()).orElseThrow(AssertionError::new).getStatus());
    }

    @Test
    void neverChangesCancelledTransactionBackToConfirmed() {
        MemoryRepository repository = new MemoryRepository();
        TransactionManager manager =
                new TransactionManager(
                        repository, new RecordingInvoker(new ArrayList<String>()), 3);
        GlobalTransaction tx = manager.begin("order", 1000);
        BranchTransaction branch =
                manager.registerBranch("one", "bean", "confirm", "cancel", new Object[0]);
        manager.markTrySucceeded(branch);
        manager.cancel(tx.getXid());
        EasyTccException error =
                assertThrows(EasyTccException.class, () -> manager.confirm(tx.getXid()));
        assertTrue(error.getMessage().contains("cannot be confirmed"));
        assertEquals(
                GlobalStatus.CANCELLED,
                repository.find(tx.getXid()).orElseThrow(AssertionError::new).getStatus());
    }

    @Test
    void propagatedContextIsValidatedAndAlwaysRestored() {
        EasyTccContext.bind("outer");
        try (EasyTccPropagation.Scope ignored = EasyTccPropagation.open("remote-123")) {
            assertEquals("remote-123", EasyTccContext.currentXid());
        }
        assertEquals("outer", EasyTccContext.currentXid());
        assertThrows(EasyTccException.class, () -> EasyTccPropagation.open("bad header"));
    }

    @Test
    void manualRetryPreservesDurableDecisionAndWritesAudit() {
        MemoryRepository repository = new MemoryRepository();
        List<String> calls = new ArrayList<String>();
        TransactionManager manager =
                new TransactionManager(repository, new RecordingInvoker(calls), 3);
        GlobalTransaction started = manager.begin("manual", 1000);
        BranchTransaction branch =
                manager.registerBranch("one", "bean", "confirm", "cancel", new Object[0]);
        manager.markTrySucceeded(branch);
        GlobalTransaction tx = repository.find(started.getXid()).orElseThrow(AssertionError::new);
        tx.setDecision(TransactionDecision.CONFIRM);
        tx.setStatus(GlobalStatus.MANUAL_INTERVENTION);
        repository.save(tx);
        manager.retryManually(tx.getXid(), "alice");
        assertEquals(Collections.singletonList("confirm:one"), calls);
        assertEquals(
                GlobalStatus.CONFIRMED,
                repository.find(tx.getXid()).orElseThrow(AssertionError::new).getStatus());
        assertTrue(
                repository.findAudit(tx.getXid(), 10).stream()
                        .anyMatch(e -> "MANUAL_RETRY".equals(e.getOperation())));
    }

    @Test
    void purgeCompletedDeletesTerminalTransactionsPastRetention() {
        MemoryRepository repository = new MemoryRepository();
        TransactionManager manager =
                new TransactionManager(
                        repository, new RecordingInvoker(new ArrayList<String>()), 3);
        GlobalTransaction old = new GlobalTransaction("old-done", "order", 1L, 2L);
        old.setStatus(GlobalStatus.CONFIRMED);
        repository.create(old);

        assertEquals(Collections.singletonList("old-done"), manager.purgeCompleted(1000L, 10));
        assertFalse(repository.find("old-done").isPresent());
        assertEquals(1L, manager.getMetrics().getPurged());
    }

    @Test
    void purgeCompletedSkipsWhenDisabledOrEmpty() {
        MemoryRepository repository = new MemoryRepository();
        TransactionManager manager =
                new TransactionManager(
                        repository, new RecordingInvoker(new ArrayList<String>()), 3);
        assertTrue(manager.purgeCompleted(0L, 10).isEmpty());
        assertTrue(manager.purgeCompleted(1000L, 0).isEmpty());
        assertEquals(0L, manager.getMetrics().getPurged());
    }

    static final class RecordingInvoker implements BranchInvoker {
        private final List<String> calls;

        RecordingInvoker(List<String> calls) {
            this.calls = calls;
        }

        public void confirm(BranchTransaction b) {
            calls.add("confirm:" + b.getName());
        }

        public void cancel(BranchTransaction b) {
            calls.add("cancel:" + b.getName());
        }
    }

    static final class MemoryRepository implements TransactionRepository {
        private final Map<String, GlobalTransaction> data =
                new HashMap<String, GlobalTransaction>();
        private final List<TransactionAuditEvent> audit = new ArrayList<TransactionAuditEvent>();

        public void create(GlobalTransaction tx) {
            data.put(tx.getXid(), copy(tx));
        }

        public Optional<GlobalTransaction> find(String xid) {
            GlobalTransaction tx = data.get(xid);
            return tx == null ? Optional.empty() : Optional.of(copy(tx));
        }

        public void save(GlobalTransaction tx) {
            data.put(tx.getXid(), copy(tx));
        }

        public boolean compareAndSet(GlobalTransaction tx, long expectedVersion) {
            GlobalTransaction current = data.get(tx.getXid());
            if (current == null || current.getVersion() != expectedVersion) return false;
            data.put(tx.getXid(), copy(tx));
            return true;
        }

        public Optional<GlobalTransaction> tryClaimRecovery(
                String xid, long expectedVersion, String owner, long leaseUntil, long now) {
            GlobalTransaction tx = data.get(xid);
            if (tx == null || tx.getVersion() != expectedVersion) return Optional.empty();
            GlobalTransaction claimed = copy(tx);
            claimed.claimRecovery(owner, leaseUntil);
            data.put(xid, copy(claimed));
            return Optional.of(claimed);
        }

        public boolean renewExecutionLease(String xid, long leaseUntil) {
            return data.containsKey(xid);
        }

        public void releaseExecutionLease(String xid) {}

        public void appendAudit(TransactionAuditEvent event) {
            audit.add(event);
        }

        public List<TransactionAuditEvent> findAudit(String xid, int limit) {
            List<TransactionAuditEvent> result = new ArrayList<TransactionAuditEvent>();
            for (TransactionAuditEvent event : audit)
                if (event.getXid().equals(xid) && result.size() < limit) result.add(event);
            return result;
        }

        public List<GlobalTransaction> findRecoverable(long now, int limit) {
            return Collections.emptyList();
        }

        public List<String> deleteTerminal(long createdBefore, int limit) {
            List<String> deleted = new ArrayList<String>();
            java.util.Iterator<Map.Entry<String, GlobalTransaction>> it =
                    data.entrySet().iterator();
            while (it.hasNext() && deleted.size() < limit) {
                GlobalTransaction tx = it.next().getValue();
                if ((tx.getStatus() == GlobalStatus.CONFIRMED
                                || tx.getStatus() == GlobalStatus.CANCELLED)
                        && tx.getCreatedAt() < createdBefore) {
                    it.remove();
                    deleted.add(tx.getXid());
                }
            }
            return deleted;
        }

        private static GlobalTransaction copy(GlobalTransaction tx) {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                new ObjectOutputStream(bytes).writeObject(tx);
                return (GlobalTransaction)
                        new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))
                                .readObject();
            } catch (IOException | ClassNotFoundException e) {
                throw new AssertionError(e);
            }
        }
    }
}
