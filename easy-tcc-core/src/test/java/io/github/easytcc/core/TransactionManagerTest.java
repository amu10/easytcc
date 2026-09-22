package io.github.easytcc.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class TransactionManagerTest {
    @AfterEach void clear() { EasyTccContext.clear(); }

    @Test void confirmsInOrderAndCancelsInReverseOrder() {
        MemoryRepository repository = new MemoryRepository();
        List<String> calls = new ArrayList<String>();
        TransactionManager manager = new TransactionManager(repository, new RecordingInvoker(calls), 3);
        GlobalTransaction tx = manager.begin("order", 1000);
        BranchTransaction one = manager.registerBranch("one", "bean", "confirm", "cancel", new Object[0]);
        manager.markTrySucceeded(one);
        BranchTransaction two = manager.registerBranch("two", "bean", "confirm", "cancel", new Object[0]);
        manager.markTrySucceeded(two);
        manager.confirm(tx.getXid());
        assertEquals(Arrays.asList("confirm:one", "confirm:two"), calls);
        assertEquals(GlobalStatus.CONFIRMED, repository.find(tx.getXid()).orElseThrow(AssertionError::new).getStatus());

        calls.clear();
        GlobalTransaction failed = manager.begin("failed-order", 1000);
        one = manager.registerBranch("one", "bean", "confirm", "cancel", new Object[0]); manager.markTrySucceeded(one);
        two = manager.registerBranch("two", "bean", "confirm", "cancel", new Object[0]); manager.markTryFailed(two, new RuntimeException("boom"));
        manager.cancel(failed.getXid());
        assertEquals(Arrays.asList("cancel:two", "cancel:one"), calls);
        assertEquals(GlobalStatus.CANCELLED, repository.find(failed.getXid()).orElseThrow(AssertionError::new).getStatus());
    }

    @Test void neverChangesCancelledTransactionBackToConfirmed() {
        MemoryRepository repository = new MemoryRepository();
        TransactionManager manager = new TransactionManager(repository, new RecordingInvoker(new ArrayList<String>()), 3);
        GlobalTransaction tx = manager.begin("order", 1000);
        BranchTransaction branch = manager.registerBranch("one", "bean", "confirm", "cancel", new Object[0]);
        manager.markTrySucceeded(branch);
        manager.cancel(tx.getXid());
        EasyTccException error = assertThrows(EasyTccException.class, () -> manager.confirm(tx.getXid()));
        assertTrue(error.getMessage().contains("cannot be confirmed"));
        assertEquals(GlobalStatus.CANCELLED, repository.find(tx.getXid()).orElseThrow(AssertionError::new).getStatus());
    }

    @Test void propagatedContextIsValidatedAndAlwaysRestored() {
        EasyTccContext.bind("outer");
        try (EasyTccPropagation.Scope ignored = EasyTccPropagation.open("remote-123")) {
            assertEquals("remote-123", EasyTccContext.currentXid());
        }
        assertEquals("outer", EasyTccContext.currentXid());
        assertThrows(EasyTccException.class, () -> EasyTccPropagation.open("bad header"));
    }

    static final class RecordingInvoker implements BranchInvoker {
        private final List<String> calls; RecordingInvoker(List<String> calls) { this.calls = calls; }
        public void confirm(BranchTransaction b) { calls.add("confirm:" + b.getName()); }
        public void cancel(BranchTransaction b) { calls.add("cancel:" + b.getName()); }
    }
    static final class MemoryRepository implements TransactionRepository {
        private final Map<String, GlobalTransaction> data = new HashMap<String, GlobalTransaction>();
        public void create(GlobalTransaction tx) { data.put(tx.getXid(), copy(tx)); }
        public Optional<GlobalTransaction> find(String xid) {
            GlobalTransaction tx = data.get(xid);
            return tx == null ? Optional.empty() : Optional.of(copy(tx));
        }
        public void save(GlobalTransaction tx) { data.put(tx.getXid(), copy(tx)); }
        public boolean compareAndSet(GlobalTransaction tx, long expectedVersion) {
            GlobalTransaction current = data.get(tx.getXid());
            if (current == null || current.getVersion() != expectedVersion) return false;
            data.put(tx.getXid(), copy(tx)); return true;
        }
        public Optional<GlobalTransaction> tryClaimRecovery(String xid, long expectedVersion, String owner, long leaseUntil, long now) {
            GlobalTransaction tx = data.get(xid);
            if (tx == null || tx.getVersion() != expectedVersion) return Optional.empty();
            GlobalTransaction claimed = copy(tx); claimed.claimRecovery(owner, leaseUntil); data.put(xid, copy(claimed));
            return Optional.of(claimed);
        }
        public boolean renewExecutionLease(String xid, long leaseUntil) { return data.containsKey(xid); }
        public void releaseExecutionLease(String xid) { }
        public List<GlobalTransaction> findRecoverable(long now, int limit) { return Collections.emptyList(); }
        private static GlobalTransaction copy(GlobalTransaction tx) {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                new ObjectOutputStream(bytes).writeObject(tx);
                return (GlobalTransaction) new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())).readObject();
            } catch (IOException | ClassNotFoundException e) { throw new AssertionError(e); }
        }
    }
}
