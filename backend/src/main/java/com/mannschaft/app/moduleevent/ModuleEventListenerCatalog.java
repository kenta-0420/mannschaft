package com.mannschaft.app.moduleevent;

import com.mannschaft.app.common.event.ModuleEventListener;
import com.mannschaft.app.common.event.ReliableEvent;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.event.ApplicationListenerMethodAdapter;
import org.springframework.context.event.EventListener;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.event.TransactionalApplicationListenerMethodAdapter;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * 起動時に確実配信リスナーの定義を検査し、固定IDとイベント型を提供する台帳。
 * storeやDBには依存せず、全件の検査に成功してから不変の台帳を公開する。
 * 未生成Beanは型メタデータだけを参照し、lazy・FactoryBean・request scopeを起動しない。
 */
@Component
public final class ModuleEventListenerCatalog implements SmartInitializingSingleton {
    private final ConfigurableListableBeanFactory beanFactory;
    private final AnnotationTransactionAttributeSource transactions = new AnnotationTransactionAttributeSource(false);
    private volatile Map<String, Class<? extends ReliableEvent>> eventTypes = Map.of();
    private volatile boolean ready;

    public ModuleEventListenerCatalog(ConfigurableListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (ready) {
            return;
        }
        Map<String, Class<? extends ReliableEvent>> modules = new LinkedHashMap<>();
        Set<String> ordinaryIds = new LinkedHashSet<>();
        Set<String> beanNames = new LinkedHashSet<>(Arrays.asList(beanFactory.getBeanDefinitionNames()));
        beanNames.addAll(Arrays.asList(beanFactory.getSingletonNames()));
        for (String beanName : beanNames) {
            // abstractな親テンプレートは生成されるBeanではないため、具体子だけを検査する。
            if (beanFactory.containsBeanDefinition(beanName)
                    && beanFactory.getBeanDefinition(beanName).isAbstract()) {
                continue;
            }
            // scoped proxyと実体を二重に数えず、実体の定義だけを検査する。
            if (beanFactory.containsBeanDefinition(ScopedProxyUtils.getTargetBeanName(beanName))) {
                continue;
            }
            Class<?> beanType = beanType(beanName);
            if (beanType != null) {
                collect(beanName, beanType, modules, ordinaryIds);
            }
        }
        eventTypes = Map.copyOf(modules);
        ready = true;
    }

    public boolean isReady() {
        return ready;
    }

    public boolean contains(String id) {
        return ready && id != null && eventTypes.containsKey(id);
    }

    public Set<String> listenerIds() {
        return ready ? eventTypes.keySet() : Set.of();
    }

    public Optional<Class<? extends ReliableEvent>> eventType(String id) {
        return ready && id != null ? Optional.ofNullable(eventTypes.get(id)) : Optional.empty();
    }

    /** getSingletonは既存実体だけを返す。getBeanによる生成やscope解決は行わない。 */
    private Class<?> beanType(String beanName) {
        Object singleton = beanFactory.getSingleton(beanName);
        if (singleton != null && !(singleton instanceof FactoryBean<?>)) {
            return ClassUtils.getUserClass(AopUtils.getTargetClass(singleton));
        }
        Class<?> predicted = beanFactory.getType(beanName, false);
        return predicted == null ? null : ClassUtils.getUserClass(predicted);
    }

    private void collect(String beanName, Class<?> beanType,
            Map<String, Class<? extends ReliableEvent>> modules, Set<String> ordinaryIds) {
        Map<Method, EventListener> methods = MethodIntrospector.selectMethods(beanType,
                (MethodIntrospector.MetadataLookup<EventListener>) method ->
                        AnnotatedElementUtils.findMergedAnnotation(method, EventListener.class));
        Set<Method> inspected = new LinkedHashSet<>();
        for (Method candidate : methods.keySet()) {
            Method method = BridgeMethodResolver.findBridgedMethod(ClassUtils.getMostSpecificMethod(candidate, beanType));
            if (!inspected.add(method)) {
                continue;
            }
            ModuleEventListener module = AnnotatedElementUtils.findMergedAnnotation(method, ModuleEventListener.class);
            if (module == null) {
                String id = new ApplicationListenerMethodAdapter(beanName, beanType, method).getListenerId();
                if (modules.containsKey(id)) {
                    throw invalid(beanName, method, "確実配信リスナーと通常リスナーのIDが重複しています");
                }
                // 通常リスナー同士には、今回の新しい一意性制約を課さない。
                ordinaryIds.add(id);
                continue;
            }
            Class<? extends ReliableEvent> eventType = validate(beanName, beanType, method, module);
            String id = new TransactionalApplicationListenerMethodAdapter(beanName, beanType, method).getListenerId();
            if (!module.id().equals(id)) {
                throw invalid(beanName, method, "明示された固定IDとSpringの登録IDが一致しません");
            }
            if (modules.containsKey(id) || ordinaryIds.contains(id)) {
                throw invalid(beanName, method, "確実配信リスナーのIDが重複しています");
            }
            modules.put(id, eventType);
        }
    }

    private Class<? extends ReliableEvent> validate(String beanName, Class<?> beanType,
            Method method, ModuleEventListener module) {
        if (!StringUtils.hasText(module.id())) {
            throw invalid(beanName, method, "空白でない固定IDを明示してください");
        }
        if (method.getParameterCount() != 1 || method.getReturnType() != void.class) {
            throw invalid(beanName, method, "引数は1つ、戻り値はvoidである必要があります");
        }
        Class<?> eventType = method.getParameterTypes()[0];
        if (!eventType.isRecord() || !ReliableEvent.class.isAssignableFrom(eventType)) {
            throw invalid(beanName, method, "引数はReliableEventを実装するrecordである必要があります");
        }
        if (transactions.getTransactionAttribute(method, beanType) != null) {
            throw invalid(beanName, method, "リスナー自身はトランザクションを開始できません");
        }
        return eventType.asSubclass(ReliableEvent.class);
    }

    private IllegalStateException invalid(String beanName, Method method, String reason) {
        return new IllegalStateException("ModuleEventListener定義が不正: bean=" + beanName
                + ", method=" + method.toGenericString() + ", reason=" + reason);
    }
}
