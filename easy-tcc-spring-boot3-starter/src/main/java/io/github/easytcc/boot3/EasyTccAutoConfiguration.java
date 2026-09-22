package io.github.easytcc.boot3;

import io.github.easytcc.core.*;
import io.github.easytcc.spring.*;
import io.github.easytcc.storage.file.FileTransactionRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import java.nio.file.Paths;

@AutoConfiguration
@ConditionalOnClass(EasyTccAspect.class)
@EnableConfigurationProperties(EasyTccProperties.class)
public class EasyTccAutoConfiguration {
    @Bean @ConditionalOnMissingBean
    public TransactionRepository easyTccRepository(EasyTccProperties p) { return new FileTransactionRepository(Paths.get(p.getFilePath())); }
    @Bean @ConditionalOnMissingBean
    public BranchInvoker easyTccBranchInvoker(ApplicationContext c) { return new SpringBranchInvoker(c); }
    @Bean @ConditionalOnMissingBean
    public TransactionManager easyTccTransactionManager(TransactionRepository r, BranchInvoker i, EasyTccProperties p) {
        return new TransactionManager(r, i, p.getMaxRetries());
    }
    @Bean public EasyTccAspect easyTccAspect(TransactionManager m) { return new EasyTccAspect(m); }
    @Bean(initMethod = "start", destroyMethod = "close") @ConditionalOnMissingBean
    public RecoveryScheduler easyTccRecoveryScheduler(TransactionRepository r, TransactionManager m, EasyTccProperties p) {
        return new RecoveryScheduler(r, m, p.getRecoveryInterval(), p.getRecoveryBatchSize());
    }
}
