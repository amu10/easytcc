package io.github.easytcc.boot3;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "easy-tcc")
public class EasyTccProperties {
    private String storage = "file";
    private String filePath = "./data/easy-tcc";
    private String redisUrl = "redis://localhost:6379";
    private int maxRetries = 20;
    private long recoveryInterval = 10000L;
    private int recoveryBatchSize = 100;
    private long purgeRetentionMillis = 604800000L;

    public String getStorage() {
        return storage;
    }

    public void setStorage(String storage) {
        this.storage = storage;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getRedisUrl() {
        return redisUrl;
    }

    public void setRedisUrl(String redisUrl) {
        this.redisUrl = redisUrl;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getRecoveryInterval() {
        return recoveryInterval;
    }

    public void setRecoveryInterval(long recoveryInterval) {
        this.recoveryInterval = recoveryInterval;
    }

    public int getRecoveryBatchSize() {
        return recoveryBatchSize;
    }

    public void setRecoveryBatchSize(int recoveryBatchSize) {
        this.recoveryBatchSize = recoveryBatchSize;
    }

    public long getPurgeRetentionMillis() {
        return purgeRetentionMillis;
    }

    public void setPurgeRetentionMillis(long purgeRetentionMillis) {
        this.purgeRetentionMillis = purgeRetentionMillis;
    }

    public void validate() {
        if (maxRetries < 0) throw new IllegalArgumentException("easy-tcc.max-retries must be >= 0");
        if (recoveryInterval <= 0)
            throw new IllegalArgumentException("easy-tcc.recovery-interval must be > 0");
        if (recoveryBatchSize <= 0)
            throw new IllegalArgumentException("easy-tcc.recovery-batch-size must be > 0");
        if (purgeRetentionMillis < 0)
            throw new IllegalArgumentException("easy-tcc.purge-retention must be >= 0");
        if (storage == null
                || !(storage.equals("file") || storage.equals("jdbc") || storage.equals("redis")))
            throw new IllegalArgumentException("easy-tcc.storage must be one of file|jdbc|redis");
    }
}
