package io.github.easytcc.storage.file;

import io.github.easytcc.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class FileTransactionRepositoryTest {
    @TempDir Path directory;
    @Test void persistsAndReloadsTransaction() {
        FileTransactionRepository repository = new FileTransactionRepository(directory);
        GlobalTransaction tx = new GlobalTransaction("test-xid", "test", 1L, 2L);
        repository.create(tx);
        tx.setStatus(GlobalStatus.CONFIRMED);
        repository.save(tx);
        assertEquals(GlobalStatus.CONFIRMED, repository.find("test-xid").orElseThrow(AssertionError::new).getStatus());
    }

    @Test void compareAndSetRejectsStaleWriteAndRecoveryClaimIsExclusive() {
        FileTransactionRepository repository = new FileTransactionRepository(directory);
        GlobalTransaction tx = new GlobalTransaction("cas-1", "order", 1L, 2L);
        repository.create(tx);
        GlobalTransaction first = repository.find("cas-1").orElseThrow(AssertionError::new);
        GlobalTransaction stale = repository.find("cas-1").orElseThrow(AssertionError::new);
        long version = first.getVersion();
        first.setStatus(GlobalStatus.CANCELLING);
        assertTrue(repository.compareAndSet(first, version));
        stale.setStatus(GlobalStatus.CONFIRMING);
        assertFalse(repository.compareAndSet(stale, version));

        GlobalTransaction recoverable = repository.find("cas-1").orElseThrow(AssertionError::new);
        assertTrue(repository.tryClaimRecovery("cas-1", recoverable.getVersion(), "node-a", 100L, 10L).isPresent());
        assertFalse(repository.tryClaimRecovery("cas-1", recoverable.getVersion(), "node-b", 100L, 10L).isPresent());
    }
}
