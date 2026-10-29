package com.mannschaft.app.moduleevent;

import com.mannschaft.app.common.event.ModuleEventListener;
import com.mannschaft.app.common.event.ReliableEvent;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.ChildBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AliasFor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 大アプリ・store・DBを起動せず、実BeanFactoryとSpring AOP検体でCatalogの起動契約を検査する。
 * 初期化前のfail-fast用状態、合成注釈、generic bridge、実効TXとID衝突を独立に検査する。
 */
class ModuleEventListenerCatalogTest {

    @Test
    void 初期化前は未準備で台帳を公開しない() {
        var catalog = catalog(new ValidListener());

        assertThat(catalog.isReady()).isFalse();
        assertThat(catalog.contains("catalog-valid")).isFalse();
        assertThat(catalog.listenerIds()).isEmpty();
        assertThat(catalog.eventType("catalog-valid")).isEmpty();
    }

    @Test
    void 起動完了後に固定IDとReliableのrecord型を公開する() {
        var catalog = catalog(new ValidListener());
        catalog.afterSingletonsInstantiated();

        assertThat(catalog.isReady()).isTrue();
        assertThat(catalog.contains("catalog-valid")).isTrue();
        assertThat(catalog.eventType("catalog-valid")).contains(ValidEvent.class);
        assertThat(catalog.listenerIds()).containsExactly("catalog-valid");
        assertThat(catalog.contains("unknown")).isFalse();
        assertThat(catalog.eventType("unknown")).isEmpty();
        assertThatThrownBy(() -> catalog.listenerIds().add("injected"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 空のBeanFactoryでも初期化完了を公開できる() {
        var catalog = catalog();
        catalog.afterSingletonsInstantiated();

        assertThat(catalog.isReady()).isTrue();
        assertThat(catalog.listenerIds()).isEmpty();
    }

    @Test
    void メタ合成注釈のAliasForで指定した固定IDを解決する() {
        var catalog = catalog(new ComposedListener());
        catalog.afterSingletonsInstantiated();

        assertThat(catalog.listenerIds()).containsExactly("composed-module");
        assertThat(catalog.eventType("composed-module")).contains(ValidEvent.class);
    }

    @Test
    void interfaceに宣言された合成注釈を実装メソッドへ解決する() {
        var catalog = catalog(new InterfaceImplementation());
        catalog.afterSingletonsInstantiated();

        assertThat(catalog.listenerIds()).containsExactly("interface-module");
        assertThat(catalog.eventType("interface-module")).contains(ValidEvent.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void JDKとCGLIBのgenericBridgeを同じ一件の定義として解決する(boolean proxyTargetClass) {
        ProxyFactory factory = new ProxyFactory(new GenericModuleListener());
        factory.setProxyTargetClass(proxyTargetClass);
        var catalog = catalog(factory.getProxy());
        catalog.afterSingletonsInstantiated();

        assertThat(catalog.listenerIds()).containsExactly("generic-module");
        assertThat(catalog.eventType("generic-module")).contains(ValidEvent.class);
    }

    @Test
    void 通常listenerにはReliableやrecordやvoidやTX禁止の新制約を課さない() {
        var catalog = catalog(new OrdinaryListener());

        assertThatCode(catalog::afterSingletonsInstantiated).doesNotThrowAnyException();
        assertThat(catalog.isReady()).isTrue();
        assertThat(catalog.listenerIds()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidModuleListeners")
    void 不正なModuleEventListenerは起動を拒否し準備済みにしない(String condition, Object listener) {
        var catalog = catalog(listener);

        assertThatThrownBy(catalog::afterSingletonsInstantiated)
                .as(condition).isInstanceOf(IllegalStateException.class);
        assertThat(catalog.isReady()).isFalse();
        assertThat(catalog.listenerIds()).isEmpty();
    }

    static Stream<Arguments> invalidModuleListeners() {
        return Stream.of(
                Arguments.of("id未指定", new MissingIdListener()),
                Arguments.of("id空白のみ", new BlankIdListener()),
                Arguments.of("通常イベント型", new NonReliableListener()),
                Arguments.of("Reliableだがrecordではない", new NonRecordListener()),
                Arguments.of("引数なし", new NoArgumentListener()),
                Arguments.of("引数二つ", new MultipleArgumentsListener()),
                Arguments.of("戻り値あり", new ReturningListener()),
                Arguments.of("methodの実効TXあり", new MethodTransactionalListener()),
                Arguments.of("classの実効TXあり", new ClassTransactionalListener()),
                Arguments.of("interfaceの実効TXあり", new InterfaceTransactionalListener()));
    }

    @Test
    void 異なるBeanの明示ModuleID重複は起動を拒否する() {
        var catalog = catalog(new ValidListener(), new ValidListener());

        assertThatThrownBy(catalog::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class);
        assertThat(catalog.isReady()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 通常listenerとの固定ID衝突も起動を拒否する(boolean transactional) {
        Object ordinary = transactional ? new CollidingTransactionalListener() : new CollidingOrdinaryListener();
        var catalog = catalog(new ValidListener(), ordinary);

        assertThatThrownBy(catalog::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class);
        assertThat(catalog.isReady()).isFalse();
    }

    @Test
    void 初期化を再呼出ししても固定台帳を維持する() {
        var catalog = catalog(new ValidListener());
        catalog.afterSingletonsInstantiated();
        catalog.afterSingletonsInstantiated();

        assertThat(catalog.isReady()).isTrue();
        assertThat(catalog.listenerIds()).containsExactly("catalog-valid");
    }

    @ParameterizedTest
    @ValueSource(strings = {"singleton", "prototype", "request"})
    void 未生成Beanはscopeを開始せず型メタデータだけで検査する(String scope) {
        AtomicInteger instances = new AtomicInteger();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        RootBeanDefinition definition = new RootBeanDefinition(ValidListener.class);
        definition.setLazyInit(true);
        definition.setScope(scope);
        definition.setInstanceSupplier(() -> {
            instances.incrementAndGet();
            return new ValidListener();
        });
        beans.registerBeanDefinition("lazy-listener", definition);
        var catalog = new ModuleEventListenerCatalog(beans);
        catalog.afterSingletonsInstantiated();

        assertThat(catalog.isReady()).isTrue();
        assertThat(catalog.listenerIds()).containsExactly("catalog-valid");
        assertThat(instances).hasValue(0);
    }

    @Test
    void FactoryBeanも生成せず宣言されたproduct型を検査する() {
        AtomicInteger instances = new AtomicInteger();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        RootBeanDefinition definition = new RootBeanDefinition(LazyListenerFactory.class);
        definition.setLazyInit(true);
        definition.setInstanceSupplier(() -> {
            instances.incrementAndGet();
            return new LazyListenerFactory();
        });
        beans.registerBeanDefinition("listener-factory", definition);
        var catalog = new ModuleEventListenerCatalog(beans);
        catalog.afterSingletonsInstantiated();

        assertThat(catalog.isReady()).isTrue();
        assertThat(catalog.listenerIds()).containsExactly("catalog-valid");
        assertThat(instances).hasValue(0);
    }

    @Test
    void requestScopedProxyは対象Beanを起動せず一件だけ検査する() {
        AtomicInteger instances = new AtomicInteger();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        RootBeanDefinition target = new RootBeanDefinition(ValidListener.class);
        target.setScope("request");
        target.setInstanceSupplier(() -> {
            instances.incrementAndGet();
            return new ValidListener();
        });
        BeanDefinitionHolder proxy = ScopedProxyUtils.createScopedProxy(
                new BeanDefinitionHolder(target, "request-listener"), beans, true);
        beans.registerBeanDefinition(proxy.getBeanName(), proxy.getBeanDefinition());
        var catalog = new ModuleEventListenerCatalog(beans);
        catalog.afterSingletonsInstantiated();

        assertThat(catalog.isReady()).isTrue();
        assertThat(catalog.listenerIds()).containsExactly("catalog-valid");
        assertThat(instances).hasValue(0);
    }

    @Test
    void 有効定義の後で検査に失敗しても部分台帳を公開しない() {
        var catalog = catalog(new ValidListener(), new MissingIdListener());

        assertThatThrownBy(catalog::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class);
        assertThat(catalog.isReady()).isFalse();
        assertThat(catalog.listenerIds()).isEmpty();
        assertThat(catalog.contains("catalog-valid")).isFalse();
        assertThat(catalog.eventType("catalog-valid")).isEmpty();
    }

    @Test
    void abstractな親定義は実Bean候補から除外して具体子だけを検査する() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        RootBeanDefinition template = new RootBeanDefinition(ValidListener.class);
        template.setAbstract(true);
        beans.registerBeanDefinition("abstract-listener-template", template);
        beans.registerBeanDefinition("concrete-listener", new ChildBeanDefinition("abstract-listener-template"));
        assertThat(beans.getBeanNamesForType(Object.class, true, false))
                .containsExactly("concrete-listener");
        var catalog = new ModuleEventListenerCatalog(beans);

        assertThatCode(catalog::afterSingletonsInstantiated).doesNotThrowAnyException();
        assertThat(catalog.isReady()).isTrue();
        assertThat(catalog.listenerIds()).containsExactly("catalog-valid");
        assertThat(catalog.eventType("catalog-valid")).contains(ValidEvent.class);
    }

    private ModuleEventListenerCatalog catalog(Object... listeners) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        for (int index = 0; index < listeners.length; index++) {
            beans.registerSingleton("listener" + index, listeners[index]);
        }
        return new ModuleEventListenerCatalog(beans);
    }

    /** 確実配信の型制約を満たす検体。 */
    record ValidEvent(UUID targetId, LocalDateTime occurredAt) implements ReliableEvent {
    }

    /** 通常listenerでのみ使う、マーカーを持たない検体。 */
    static class OrdinaryEvent {
    }

    /** record要件違反だけを切り離した検体。 */
    static class NonRecordEvent implements ReliableEvent {
        @Override
        public LocalDateTime occurredAt() {
            return LocalDateTime.MIN;
        }
    }

    /** 有効な直接注釈の検体。 */
    static class ValidListener {
        @ModuleEventListener(id = "catalog-valid")
        public void accept(ValidEvent event) {
        }
    }

    /** 型予測だけで検査すべきFactoryBean。product生成はこの試練では禁止する。 */
    static class LazyListenerFactory implements FactoryBean<ValidListener> {
        @Override
        public ValidListener getObject() {
            throw new AssertionError("CatalogがFactoryBeanのproductを生成した");
        }

        @Override
        public Class<?> getObjectType() {
            return ValidListener.class;
        }
    }

    /** ModuleEventListenerのidを合成注釈から指定する検体。 */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @ModuleEventListener
    @interface ComposedModuleEventListener {
        @AliasFor(annotation = ModuleEventListener.class, attribute = "id")
        String id();
    }

    /** メタ合成注釈を付けたlistener。 */
    static class ComposedListener {
        @ComposedModuleEventListener(id = "composed-module")
        public void accept(ValidEvent event) {
        }
    }

    /** interface側だけに注釈を置いた契約。 */
    interface AnnotatedContract {
        @ModuleEventListener(id = "interface-module")
        void accept(ValidEvent event);
    }

    /** interface注釈の探索を検証する実装。 */
    static class InterfaceImplementation implements AnnotatedContract {
        @Override
        public void accept(ValidEvent event) {
        }
    }

    /** javacにgeneric bridgeを生成させる契約。 */
    interface GenericContract<T> {
        void accept(T event);
    }

    /** 具象引数のModuleEventListenerから生成されるbridgeを重複計上させない検体。 */
    static class GenericModuleListener implements GenericContract<ValidEvent> {
        @Override
        @ModuleEventListener(id = "generic-module")
        public void accept(ValidEvent event) {
        }
    }

    /** 通常listenerの形は今回の新制約に巻き込まない。 */
    @Transactional
    static class OrdinaryListener {
        @EventListener(id = "ordinary-listener")
        public Set<String> ordinary(OrdinaryEvent event) {
            return Set.of();
        }

        @TransactionalEventListener(id = "ordinary-transactional-listener")
        public void transactional(OrdinaryEvent event) {
        }
    }

    /** 未指定idの検体。 */
    static class MissingIdListener {
        @ModuleEventListener
        public void accept(ValidEvent event) {
        }
    }

    /** 空白idの検体。 */
    static class BlankIdListener {
        @ModuleEventListener(id = "  ")
        public void accept(ValidEvent event) {
        }
    }

    /** ReliableEventを実装していない型の検体。 */
    static class NonReliableListener {
        @ModuleEventListener(id = "non-reliable")
        public void accept(OrdinaryEvent event) {
        }
    }

    /** recordでない型の検体。 */
    static class NonRecordListener {
        @ModuleEventListener(id = "non-record")
        public void accept(NonRecordEvent event) {
        }
    }

    /** 引数なしの検体。 */
    static class NoArgumentListener {
        @ModuleEventListener(id = "no-argument")
        public void accept() {
        }
    }

    /** 引数二つの検体。 */
    static class MultipleArgumentsListener {
        @ModuleEventListener(id = "multiple-arguments")
        public void accept(ValidEvent event, String extra) {
        }
    }

    /** voidでない戻り値の検体。 */
    static class ReturningListener {
        @ModuleEventListener(id = "returning")
        public String accept(ValidEvent event) {
            return "invalid";
        }
    }

    /** メソッド自身でTXを始める検体。 */
    static class MethodTransactionalListener {
        @Transactional
        @ModuleEventListener(id = "method-tx")
        public void accept(ValidEvent event) {
        }
    }

    /** クラスからTXを継承する検体。 */
    @Transactional
    static class ClassTransactionalListener {
        @ModuleEventListener(id = "class-tx")
        public void accept(ValidEvent event) {
        }
    }

    /** interfaceから実効TXを取得する契約。 */
    interface TransactionalContract {
        @Transactional
        void accept(ValidEvent event);
    }

    /** 具象methodにTX注釈がなくても禁止する検体。 */
    static class InterfaceTransactionalListener implements TransactionalContract {
        @Override
        @ModuleEventListener(id = "interface-tx")
        public void accept(ValidEvent event) {
        }
    }

    /** 通常EventListenerの明示idとの衝突検体。 */
    static class CollidingOrdinaryListener {
        @EventListener(id = "catalog-valid")
        public void accept(OrdinaryEvent event) {
        }
    }

    /** 通常TransactionalEventListenerの明示idとの衝突検体。 */
    static class CollidingTransactionalListener {
        @TransactionalEventListener(id = "catalog-valid")
        public void accept(OrdinaryEvent event) {
        }
    }
}
