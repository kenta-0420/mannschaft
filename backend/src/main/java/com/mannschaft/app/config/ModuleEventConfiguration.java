package com.mannschaft.app.config;

import com.mannschaft.app.moduleevent.RestrictedEventPublicationRegistry;
import com.mannschaft.app.moduleevent.ModuleEventExecutionFrame;
import com.mannschaft.app.moduleevent.ModuleEventTaskExecutor;
import com.mannschaft.app.moduleevent.ModuleEventPublicationStore;
import com.mannschaft.app.moduleevent.ModuleEventListenerCatalog;
import com.mannschaft.app.moduleevent.api.spi.ModuleEventImpactResolver;
import com.mannschaft.app.moduleevent.notice.ModuleEventImpactEvaluator;
import com.mannschaft.app.moduleevent.notice.ModuleEventImpactResolverRegistry;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.support.CompletionRegisteringAdvisor;
import org.springframework.modulith.events.support.PersistentApplicationEventMulticaster;
import org.springframework.transaction.PlatformTransactionManager;

/** 公式 JPA 保存と公開イベント基盤を組み合わせる。既定 Registry の二重生成を避ける。 */
@Configuration(proxyBeanMethods = false)
public class ModuleEventConfiguration {
    /** 公式 JPA repository の実装型は非公開なので、公開 SPI と Bean 名で受け取る。 */
    @Bean
    public RestrictedEventPublicationRegistry moduleEventRegistry(
            @Qualifier("jpaEventPublicationRepository") EventPublicationRepository repository,
            EntityManager entityManager, ModuleEventListenerCatalog catalog,
            PlatformTransactionManager transactions, ObjectProvider<ModuleEventImpactResolverRegistry> impacts,
            ModuleEventExecutionFrame.Context frames) {
        return new RestrictedEventPublicationRegistry(repository, Clock.systemUTC(), entityManager,
                catalog, transactions, impacts, frames);
    }

    @Bean
    public ModuleEventExecutionFrame.Context moduleEventExecutionContext() {
        return new ModuleEventExecutionFrame.Context();
    }

    /** 初回保存と独立した短TXを区別する内部Store。 */
    @Bean
    public ModuleEventPublicationStore moduleEventPublicationStore(EntityManager entityManager,
            @Qualifier("jpaEventPublicationRepository") EventPublicationRepository repository,
            EventSerializer serializer, ModuleEventListenerCatalog catalog) {
        return new ModuleEventPublicationStore(entityManager, repository, serializer, catalog, Clock.systemUTC());
    }

    /** 受信者評価を含まない純識別窓口。業務resolverの依存をM Registryの構築へ戻さない。 */
    @Bean
    public ModuleEventImpactResolverRegistry moduleEventImpactResolverRegistry(
            ObjectProvider<ModuleEventImpactResolver> resolvers) {
        return new ModuleEventImpactResolverRegistry(resolvers.orderedStream().toList());
    }

    /** 業務の現在評価は保存hookと分け、非TX段取りから呼ぶ。 */
    @Bean
    public ModuleEventImpactEvaluator moduleEventImpactEvaluator(ModuleEventImpactResolverRegistry registry) {
        return new ModuleEventImpactEvaluator(registry);
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
    public ModuleEventTaskExecutor moduleEventPool(ModuleEventExecutionFrame.Context frames,
            ObjectProvider<RestrictedEventPublicationRegistry> registry) {
        // M18未実証の仮値。frameなしの従来taskも同じ容量で受付する。
        ModuleEventTaskExecutor executor = new ModuleEventTaskExecutor(frames,
                new ModuleEventExecutionFrame.Owner() {
                    @Override public boolean admit(ModuleEventExecutionFrame frame) {
                        return registry.getObject().admit(frame);
                    }
                    @Override public void rejected(ModuleEventExecutionFrame frame) {
                        registry.getObject().rejected(frame);
                    }
                    @Override public void finished(ModuleEventExecutionFrame frame) {
                        registry.getObject().finished(frame);
                    }
                }, 200, new AsyncConfig.MdcTaskDecorator());
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("module-event-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
