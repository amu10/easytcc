package io.github.easytcc.boot2;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "easy-tcc")
public class EasyTccProperties {
    private String filePath = "./data/easy-tcc";
    private int maxRetries = 20;
    private long recoveryInterval = 10000L;
    private int recoveryBatchSize = 100;
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public long getRecoveryInterval() { return recoveryInterval; }
    public void setRecoveryInterval(long recoveryInterval) { this.recoveryInterval = recoveryInterval; }
    public int getRecoveryBatchSize() { return recoveryBatchSize; }
    public void setRecoveryBatchSize(int recoveryBatchSize) { this.recoveryBatchSize = recoveryBatchSize; }
}
