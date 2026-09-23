package io.github.easytcc.storage.file;

import io.github.easytcc.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class FileTransactionRepository implements TransactionRepository {
    private final Path directory;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public FileTransactionRepository(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.directory);
        } catch (IOException e) {
            throw new EasyTccException("Cannot create transaction directory: " + directory, e);
        }
    }

    @Override
    public void create(GlobalTransaction transaction) {
        lock.writeLock().lock();
        try {
            Path target = file(transaction.getXid());
            if (Files.exists(target))
                throw new EasyTccException("Transaction already exists: " + transaction.getXid());
            write(transaction, target);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<GlobalTransaction> find(String xid) {
        lock.readLock().lock();
        try {
            Path path = file(xid);
            if (!Files.exists(path)) return Optional.empty();
            try (ObjectInputStream input =
                    new ObjectInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                return Optional.of((GlobalTransaction) input.readObject());
            } catch (IOException | ClassNotFoundException e) {
                throw new EasyTccException("Cannot read transaction: " + xid, e);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void save(GlobalTransaction transaction) {
        lock.writeLock().lock();
        try {
            write(transaction, file(transaction.getXid()));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean compareAndSet(GlobalTransaction transaction, long expectedVersion) {
        lock.writeLock().lock();
        try {
            Optional<GlobalTransaction> current = readUnlocked(transaction.getXid());
            if (!current.isPresent() || current.get().getVersion() != expectedVersion) return false;
            write(transaction, file(transaction.getXid()));
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<GlobalTransaction> tryClaimRecovery(
            String xid, long expectedVersion, String owner, long leaseUntil, long now) {
        lock.writeLock().lock();
        try {
            Optional<GlobalTransaction> value = readUnlocked(xid);
            if (!value.isPresent()) return Optional.empty();
            GlobalTransaction tx = value.get();
            if (tx.getVersion() != expectedVersion || !recoverable(tx, now))
                return Optional.empty();
            if (tx.getRecoveryOwner() != null && tx.getRecoveryLeaseUntil() > now)
                return Optional.empty();
            tx.claimRecovery(owner, leaseUntil);
            write(tx, file(xid));
            return Optional.of(tx);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean renewExecutionLease(String xid, long leaseUntil) {
        lock.writeLock().lock();
        try {
            Optional<GlobalTransaction> value = readUnlocked(xid);
            if (!value.isPresent()) return false;
            value.get().setExecutionLeaseUntil(leaseUntil);
            write(value.get(), file(xid));
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void releaseExecutionLease(String xid) {
        renewExecutionLease(xid, 0L);
    }

    @Override
    public Map<GlobalStatus, Long> countByStatus() {
        Map<GlobalStatus, Long> counts = new EnumMap<GlobalStatus, Long>(GlobalStatus.class);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.tx")) {
            for (Path path : stream) {
                GlobalTransaction tx =
                        find(stripSuffix(path.getFileName().toString())).orElse(null);
                if (tx != null)
                    counts.put(
                            tx.getStatus(),
                            counts.containsKey(tx.getStatus())
                                    ? counts.get(tx.getStatus()) + 1L
                                    : 1L);
            }
            return counts;
        } catch (IOException e) {
            throw new EasyTccException("Cannot count transaction states", e);
        }
    }

    @Override
    public List<GlobalTransaction> findRecoverable(long now, int limit) {
        List<GlobalTransaction> result = new ArrayList<GlobalTransaction>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.tx")) {
            for (Path path : stream) {
                Optional<GlobalTransaction> value =
                        find(stripSuffix(path.getFileName().toString()));
                if (value.isPresent() && recoverable(value.get(), now)) result.add(value.get());
                if (result.size() >= limit) break;
            }
        } catch (IOException e) {
            throw new EasyTccException("Cannot scan transaction directory", e);
        }
        return result;
    }

    @Override
    public List<String> deleteTerminal(long createdBefore, int limit) {
        if (limit <= 0) return Collections.emptyList();
        List<String> deleted = new ArrayList<String>();
        lock.writeLock().lock();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.tx")) {
            for (Path path : stream) {
                if (deleted.size() >= limit) break;
                String xid = stripSuffix(path.getFileName().toString());
                Optional<GlobalTransaction> value = readUnlocked(xid);
                if (!value.isPresent()) continue;
                GlobalTransaction tx = value.get();
                if (!isTerminal(tx.getStatus()) || tx.getCreatedAt() >= createdBefore) continue;
                Files.delete(path);
                deleted.add(xid);
            }
        } catch (IOException e) {
            throw new EasyTccException("Cannot purge terminal transactions", e);
        } finally {
            lock.writeLock().unlock();
        }
        return deleted;
    }

    private static boolean isTerminal(GlobalStatus status) {
        return status == GlobalStatus.CONFIRMED || status == GlobalStatus.CANCELLED;
    }

    private void write(GlobalTransaction transaction, Path target) {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(temporary.toFile());
                ObjectOutputStream output =
                        new ObjectOutputStream(new BufferedOutputStream(stream))) {
            output.writeObject(transaction);
            output.flush();
            stream.getFD().sync();
        } catch (IOException e) {
            throw new EasyTccException("Cannot persist transaction: " + transaction.getXid(), e);
        }
        try {
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new EasyTccException("Cannot replace transaction file: " + target, e);
        }
    }

    private Optional<GlobalTransaction> readUnlocked(String xid) {
        Path path = file(xid);
        if (!Files.exists(path)) return Optional.empty();
        try (ObjectInputStream input =
                new ObjectInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            return Optional.of((GlobalTransaction) input.readObject());
        } catch (IOException | ClassNotFoundException e) {
            throw new EasyTccException("Cannot read transaction: " + xid, e);
        }
    }

    private Path file(String xid) {
        if (!xid.matches("[a-zA-Z0-9-]+")) throw new IllegalArgumentException("Invalid xid");
        return directory.resolve(xid + ".tx");
    }

    private static String stripSuffix(String name) {
        return name.substring(0, name.length() - 3);
    }

    private static boolean recoverable(GlobalTransaction tx, long now) {
        GlobalStatus s = tx.getStatus();
        if (tx.getExecutionLeaseUntil() > now) return false;
        if (s == GlobalStatus.TRYING && tx.getDeadline() <= now) return true;
        return (s == GlobalStatus.CONFIRMING
                        || s == GlobalStatus.CONFIRM_FAILED
                        || s == GlobalStatus.CANCELLING
                        || s == GlobalStatus.CANCEL_FAILED)
                && tx.getNextRetryAt() <= now;
    }
}
