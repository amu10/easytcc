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
}
