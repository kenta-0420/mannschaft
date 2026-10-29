package com.mannschaft.app.config;

import com.mannschaft.app.moduleevent.RestrictedEventPublicationRegistry;
import com.mannschaft.app.moduleevent.ModuleEventListenerCatalog;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.support.CompletionRegisteringAdvisor;
import org.springframework.modulith.events.support.PersistentApplicationEventMulticaster;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

/** 公式 JPA 保存と公開イベント基盤を組み合わせる。既定 Registry の二重生成を避ける。 */
@Configuration(proxyBeanMethods = false)
public class ModuleEventConfiguration {
    /** 公式 JPA repository の実装型は非公開なので、公開 SPI と Bean 名で受け取る。 */
    @Bean
    public RestrictedEventPublicationRegistry moduleEventRegistry(
            @Qualifier("jpaEventPublicationRepository") EventPublicationRepository repository,
            EntityManager entityManager, ModuleEventListenerCatalog catalog,
            PlatformTransactionManager transactions) {
        return new RestrictedEventPublicationRegistry(repository, Clock.systemUTC(), entityManager,
                catalog, transactions);
    }

    /** 循環初期化を避け、Registry はイベント発行時に解決する。 */
    @Bean(name = "applicationEventMulticaster")
    public static PersistentApplicationEventMulticaster applicationEventMulticaster(
            ObjectProvider<EventPublicationRegistry> registry, ObjectProvider<Environment> environment) {
        return new PersistentApplicationEventMulticaster(registry::getObject, environment::getObject);
    }

    /** 非 TX listener の成功・失敗を Registry へ返す公式 advisor。 */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static CompletionRegisteringAdvisor completionRegisteringAdvisor(
            ObjectProvider<EventPublicationRegistry> registry) {
        return new CompletionRegisteringAdvisor(registry::getObject);
    }

    /** 初期容量は負荷試験で確定する。拒否を捨てず呼出元へ返す。 */
    @Bean("module-event-pool")
    public Executor moduleEventPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("module-event-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setTaskDecorator(new AsyncConfig.MdcTaskDecorator());
        executor.initialize();
        return executor;
    }
}
