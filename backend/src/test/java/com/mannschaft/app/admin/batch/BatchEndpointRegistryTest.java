package com.mannschaft.app.admin.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
                    .hasCauseInstanceOf(IllegalStateException.class)
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

    /** Registry だけを Bean 登録する最小コンテナ設定。 */
    @Configuration
    static class RegistryOnlyConfig {
        @Bean
        public BatchEndpointRegistry batchEndpointRegistry(GenericApplicationContext context) {
            return new BatchEndpointRegistry(context);
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
