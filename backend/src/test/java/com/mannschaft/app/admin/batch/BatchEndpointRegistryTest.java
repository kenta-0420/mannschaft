package com.mannschaft.app.admin.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link BatchEndpointRegistry} の単体テスト。
 *
 * <p>本テストでは Spring の最小コンテナを {@link AnnotationConfigApplicationContext} で起動し、
 * {@link BatchEndpoint} 付きメソッドの収集・重複検出を検証する。</p>
 */
@DisplayName("BatchEndpointRegistry 単体テスト")
class BatchEndpointRegistryTest {

    @Test
    @DisplayName("@BatchEndpoint 付きメソッドが収集される")
    void shouldCollectAnnotatedMethods() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(RegistryOnlyConfig.class, SampleBatchBean.class);
            ctx.refresh();

            BatchEndpointRegistry registry = ctx.getBean(BatchEndpointRegistry.class);
            List<BatchEndpointDescriptor> list = registry.listAll();
            assertThat(list).hasSize(2);
            assertThat(registry.find("sample-foo")).isPresent();
            assertThat(registry.find("sample-bar")).isPresent();
            assertThat(registry.find("unknown")).isEmpty();

            BatchEndpointDescriptor foo = registry.find("sample-foo").orElseThrow();
            assertThat(foo.name()).isEqualTo("sample-foo");
            assertThat(foo.description()).isEqualTo("foo desc");
            assertThat(foo.method().getName()).isEqualTo("foo");
        }
    }

    @Test
    @DisplayName("重複 name で FAIL FAST する")
    void shouldFailFastOnDuplicateName() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(RegistryOnlyConfig.class, DuplicateNameBeanA.class, DuplicateNameBeanB.class);
            assertThatThrownBy(ctx::refresh)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("dup-name");
        }
    }

    @Test
    @DisplayName("invoke で対象メソッドが呼ばれる")
    void shouldInvokeMethodByName() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(RegistryOnlyConfig.class, SampleBatchBean.class);
            ctx.refresh();
            SampleBatchBean bean = ctx.getBean(SampleBatchBean.class);
            BatchEndpointRegistry registry = ctx.getBean(BatchEndpointRegistry.class);

            registry.invoke("sample-foo");
            assertThat(bean.fooCalled).isTrue();
        }
    }

    @Test
    @DisplayName("未登録 name の invoke は IllegalArgumentException")
    void shouldThrowOnUnknownInvoke() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(RegistryOnlyConfig.class, SampleBatchBean.class);
            ctx.refresh();
            BatchEndpointRegistry registry = ctx.getBean(BatchEndpointRegistry.class);
            assertThatThrownBy(() -> registry.invoke("ghost"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("scan-enabled=false なら走査せず、遅延 Bean も生成しない")
    void shouldSkipScanWhenDisabled() {
        LazyProbeBean.instantiated = false;
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(RegistryScanDisabledConfig.class, SampleBatchBean.class);
            ctx.refresh();

            BatchEndpointRegistry registry = ctx.getBean(BatchEndpointRegistry.class);
            assertThat(registry.listAll()).isEmpty();
            assertThat(registry.find("sample-foo")).isEmpty();
            // 走査が @Lazy Bean を強制生成していないこと（起動時間削減の実体はここ）
            assertThat(LazyProbeBean.instantiated).isFalse();
        }
    }

    @Test
    @DisplayName("scan-enabled=true なら走査し、@Lazy Bean も強制生成される")
    void shouldScanAndForceLazyBeansWhenEnabled() {
        LazyProbeBean.instantiated = false;
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(RegistryOnlyConfig.class, SampleBatchBean.class);
            ctx.refresh();

            BatchEndpointRegistry registry = ctx.getBean(BatchEndpointRegistry.class);
            assertThat(registry.listAll()).hasSize(2);
            // 全 Bean 定義に getBean() を掛けるため、@Lazy Bean も生成されてしまう
            // （= spring.main.lazy-initialization を打ち消す。無効化の意味がここにある）
            assertThat(LazyProbeBean.instantiated).isTrue();
        }
    }

    @Test
    @DisplayName("scan-enabled 未指定なら既定 true として走査する")
    void shouldDefaultToEnabledWhenPropertyAbsent() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(PlaceholderConfig.class, BatchEndpointRegistry.class, SampleBatchBean.class);
            ctx.refresh();

            BatchEndpointRegistry registry = ctx.getBean(BatchEndpointRegistry.class);
            assertThat(registry.listAll()).hasSize(2);
        }
    }

    /** {@code @Value} の既定値解決を有効にするための設定。 */
    @Configuration
    static class PlaceholderConfig {
        @Bean
        public static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }
    }

    /** 走査を無効化した Registry を登録する設定。 */
    @Configuration
    static class RegistryScanDisabledConfig {
        @Bean
        public BatchEndpointRegistry batchEndpointRegistry(GenericApplicationContext context) {
            return new BatchEndpointRegistry(context, false);
        }

        @Bean
        @Lazy
        public LazyProbeBean lazyProbeBean() {
            return new LazyProbeBean();
        }
    }

    /** 走査が遅延 Bean を強制生成するかどうかを観測するための Bean。 */
    static class LazyProbeBean {
        static boolean instantiated = false;

        LazyProbeBean() {
            instantiated = true;
        }
    }

    /** Registry だけを Bean 登録する最小コンテナ設定。 */
    @Configuration
    static class RegistryOnlyConfig {
        @Bean
        public BatchEndpointRegistry batchEndpointRegistry(GenericApplicationContext context) {
            return new BatchEndpointRegistry(context, true);
        }

        @Bean
        @Lazy
        public LazyProbeBean lazyProbeBean() {
            return new LazyProbeBean();
        }
    }

    @Component
    static class SampleBatchBean {
        boolean fooCalled = false;
        boolean barCalled = false;

        @Scheduled(fixedDelay = Long.MAX_VALUE)
        @BatchEndpoint(name = "sample-foo", description = "foo desc")
        public void foo() {
            fooCalled = true;
        }

        @Scheduled(fixedDelay = Long.MAX_VALUE)
        @BatchEndpoint(name = "sample-bar")
        public void bar() {
            barCalled = true;
        }
    }

    @Component
    static class DuplicateNameBeanA {
        @BatchEndpoint(name = "dup-name")
        public void a() {
        }
    }

    @Component
    static class DuplicateNameBeanB {
        @BatchEndpoint(name = "dup-name")
        public void b() {
        }
    }

    // Spring が @Configuration をスキャンしなくても Bean Definition を持つよう、Component を Bean 登録するためのヘルパー。
    static {
        // no-op: 何もしない (register() で直接渡しているため不要)
    }

    // BeanDefinition の存在を assert したい時に使う static helper (今回未使用)
    @SuppressWarnings("unused")
    private static void assertHasBean(GenericApplicationContext ctx, String name) {
        BeanDefinition def = ctx.getBeanDefinition(name);
        assertThat(def).isNotNull();
    }
}
