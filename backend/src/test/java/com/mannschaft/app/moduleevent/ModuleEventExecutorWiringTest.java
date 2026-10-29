package com.mannschaft.app.moduleevent;

import com.mannschaft.app.config.AsyncConfig;
import com.mannschaft.app.config.ModuleEventConfiguration;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.websocket.servlet.WebSocketMessagingAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.config.AbstractMessageBrokerConfiguration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** 実Config/Boot factory/Asyncを小Contextで通す。主App・DB・WebSocket接続は起動しない。 */
class ModuleEventExecutorWiringTest {
    @Test
    void 同型候補が他に二つあってもRegistryは自分のpoolだけを予約する() {
        try (Harness h = new Harness(true)) {
            var own = h.pool("module-event-pool");
            var inbound = h.pool("clientInboundChannelExecutor");
            var outbound = h.pool("clientOutboundChannelExecutor");
            h.recording.capacity = own::availableCapacity;
            var registry = h.context.getBean(RestrictedEventPublicationRegistry.class);
            assertDoesNotThrow(() -> registry.processIncompletePublications(publication -> true,
                    publication -> { throw new AssertionError("空のclaimから配信しない"); }, Duration.ofMinutes(5)));
            assertThat(h.recording.claims).hasValue(1);
            assertThat(h.recording.capacityInsideClaim).isEqualTo(100);
            assertThat(own.availableCapacity()).isEqualTo(200);
            assertThat(inbound.availableCapacity()).isEqualTo(200);
            assertThat(outbound.availableCapacity()).isEqualTo(200);
        }
    }

    @Test
    void Bootの実WebSocket選択はM専用poolを入出力へ流用しない() {
        try (Harness h = new Harness(false)) {
            var configurer = h.context.getBean("bootExecutorConfigurer", WebSocketMessageBrokerConfigurer.class);
            BrokerProbe broker = new BrokerProbe(configurer);
            Executor inbound = broker.clientInboundChannelExecutor();
            Executor outbound = broker.clientOutboundChannelExecutor();
            var own = h.pool("module-event-pool");
            assertThat(inbound).isNotSameAs(own).isNotInstanceOf(ModuleEventTaskExecutor.class);
            assertThat(outbound).isNotSameAs(own).isNotInstanceOf(ModuleEventTaskExecutor.class);
            assertThat(inbound).isNotSameAs(outbound);
        }
    }

    @Test
    void 明示Qualifierと名前付きAsyncは候補除外後も専用poolへ届く() throws Exception {
        try (Harness h = new Harness(false)) {
            var own = h.pool("module-event-pool");
            var qualified = h.context.getBean(QualifiedConsumer.class);
            assertThat(qualified.executor).isSameAs(own);
            AsyncProbe probe = h.context.getBean(AsyncProbe.class);
            CountDownLatch ran = new CountDownLatch(1);
            probe.run(ran);
            assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(own.getThreadPoolExecutor().getTaskCount()).isEqualTo(1);
        }
    }

    /** Configの@Beanを全て実登録し、DBが必要なStoreだけ局所の空claim検体へ置き換える。 */
    private static final class Harness implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final RecordingStore recording = new RecordingStore();
        Harness(boolean peers) {
            context.register(Fixtures.class);
            context.registerBean(ModuleEventListenerCatalog.class, () -> new ModuleEventListenerCatalog(context.getBeanFactory()));
            context.registerBean("jpaEventPublicationRepository", EventPublicationRepository.class,
                    ModuleEventExecutorWiringTest::unusedRepository);
            context.registerBean("recordingStore", RecordingStore.class, () -> recording, definition -> definition.setPrimary(true));
            context.registerBean(QualifiedConsumer.class);
            context.registerBean(AsyncProbe.class);
            if (peers) {
                context.registerBean("clientInboundChannelExecutor", ModuleEventTaskExecutor.class, ModuleEventExecutorWiringTest::peer);
                context.registerBean("clientOutboundChannelExecutor", ModuleEventTaskExecutor.class, ModuleEventExecutorWiringTest::peer);
            }
            // 公開Spring factory定義がBootの実@Bean methodを呼ぶ。非公開classへの反射アクセスはしない。
            context.getBeanFactory().registerSingleton("bootWebSocketConfiguration", new WebSocketMessagingAutoConfiguration());
            RootBeanDefinition boot = new RootBeanDefinition();
            boot.setFactoryBeanName("bootWebSocketConfiguration");
            boot.setFactoryMethodName("webSocketMessageBrokerExecutorConfigurer");
            boot.setAutowireMode(AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
            context.registerBeanDefinition("bootExecutorConfigurer", boot);
            // 通常poolは既存Executor戻り型の定義段階に置く。Bootの早期型選択を再現する。
            context.addBeanFactoryPostProcessor(factory -> {
                for (String name : factory.getBeanDefinitionNames()) factory.getBeanDefinition(name).setLazyInit(true);
            });
            context.refresh();
        }
        ModuleEventTaskExecutor pool(String name) { return context.getBean(name, ModuleEventTaskExecutor.class); }
        @Override public void close() { context.close(); }
    }

    private static EventPublicationRepository unusedRepository() {
        return (EventPublicationRepository) Proxy.newProxyInstance(EventPublicationRepository.class.getClassLoader(),
                new Class<?>[]{EventPublicationRepository.class}, (proxy, method, arguments) -> {
                    return switch (method.getName()) {
                        case "toString" -> "未使用の公式Repository検体";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == arguments[0];
                        default -> throw new AssertionError("空claim試練でRepositoryを呼ばない: " + method.getName());
                    };
                });
    }

    private static ModuleEventTaskExecutor peer() {
        var executor = new ModuleEventTaskExecutor(new ModuleEventExecutionFrame.Context(), new ModuleEventExecutionFrame.Owner() {
            @Override public boolean admit(ModuleEventExecutionFrame frame) { throw new AssertionError("他poolを使わない"); }
            @Override public void rejected(ModuleEventExecutionFrame frame) { throw new AssertionError("他poolを使わない"); }
            @Override public void finished(ModuleEventExecutionFrame frame) { throw new AssertionError("他poolを使わない"); }
        }, 200, task -> task);
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        return executor;
    }

    public static class RecordingStore extends ModuleEventPublicationStore {
        final AtomicInteger claims = new AtomicInteger();
        IntSupplier capacity = () -> -1;
        int capacityInsideClaim;
        RecordingStore() { super(null, null, null, null, Clock.systemUTC()); }
        @Override public List<Claim> claim(Instant cutoff, Instant now, int limit) {
            claims.incrementAndGet();
            capacityInsideClaim = capacity.getAsInt();
            return List.of();
        }
    }

    public static class QualifiedConsumer {
        final ModuleEventTaskExecutor executor;
        public QualifiedConsumer(@Qualifier("module-event-pool") ModuleEventTaskExecutor executor) { this.executor = executor; }
    }
    public static class AsyncProbe {
        @Async("module-event-pool")
        public void run(CountDownLatch ran) { ran.countDown(); }
    }

    private static final class BrokerProbe extends AbstractMessageBrokerConfiguration {
        private final WebSocketMessageBrokerConfigurer configurer;
        BrokerProbe(WebSocketMessageBrokerConfigurer configurer) { this.configurer = configurer; }
        @Override protected void configureClientInboundChannel(ChannelRegistration registration) { configurer.configureClientInboundChannel(registration); }
        @Override protected void configureClientOutboundChannel(ChannelRegistration registration) { configurer.configureClientOutboundChannel(registration); }
        @Override protected SimpUserRegistry createLocalUserRegistry(Integer order) { throw new AssertionError("executor選択だけを測る"); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import({ModuleEventConfiguration.class, AsyncConfig.class})
    private static class Fixtures { }
}
