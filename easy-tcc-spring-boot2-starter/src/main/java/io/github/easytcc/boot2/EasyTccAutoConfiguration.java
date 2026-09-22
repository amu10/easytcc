package io.github.easytcc.boot2;

import io.github.easytcc.core.*;
import io.github.easytcc.spring.*;
import io.github.easytcc.storage.file.FileTransactionRepository;
import io.github.easytcc.storage.jdbc.JdbcTransactionRepository;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Configuration;
import java.nio.file.Paths;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(EasyTccAspect.class)
@EnableConfigurationProperties(EasyTccProperties.class)
public class EasyTccAutoConfiguration {
    @Bean @ConditionalOnMissingBean @ConditionalOnProperty(prefix = "easy-tcc", name = "storage", havingValue = "file", matchIfMissing = true)
    public TransactionRepository easyTccRepository(EasyTccProperties p) { return new FileTransactionRepository(Paths.get(p.getFilePath())); }
    @Bean @ConditionalOnMissingBean @ConditionalOnProperty(prefix = "easy-tcc", name = "storage", havingValue = "jdbc")
    public TransactionRepository easyTccJdbcRepository(javax.sql.DataSource dataSource) { return new JdbcTransactionRepository(dataSource); }
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
    @Configuration @ConditionalOnClass(name = "javax.servlet.Filter")
    static class ServletPropagationConfiguration {
        @Bean @ConditionalOnMissingBean EasyTccPropagationFilter easyTccPropagationFilter() { return new EasyTccPropagationFilter(); }
    }
    @Configuration @ConditionalOnClass(name = "org.springframework.web.client.RestTemplate")
    static class RestPropagationConfiguration {
        @Bean @ConditionalOnMissingBean EasyTccRestTemplateInterceptor easyTccRestTemplateInterceptor() { return new EasyTccRestTemplateInterceptor(); }
    }
    @Configuration @ConditionalOnClass(name = "feign.RequestInterceptor")
    static class FeignPropagationConfiguration {
        @Bean @ConditionalOnMissingBean EasyTccFeignInterceptor easyTccFeignInterceptor() { return new EasyTccFeignInterceptor(); }
    }
}
