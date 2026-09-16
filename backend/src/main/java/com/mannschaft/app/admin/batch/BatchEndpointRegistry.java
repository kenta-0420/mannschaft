package com.mannschaft.app.admin.batch;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * F10.X 第一陣 — {@link BatchEndpoint} 付きメソッドの登録レジストリ。
 *
 * <p>Spring の全 Bean を起動完了時に走査し、{@link BatchEndpoint} が付いたメソッドを
 * {@link BatchEndpointDescriptor} として収集する。重複した {@link BatchEndpoint#name()} が
 * 検出された場合は {@link IllegalStateException} で起動失敗させる（FAIL FAST）。</p>
 *
 * <p>運用画面からの実機実行や、第二陣以降の REST 起点呼び出しは {@link #invoke(String)} を経由する。
 * リフレクションで Bean のメソッドを呼ぶため、{@code @Transactional} などの AOP は通常通り効く。</p>
 *
 * <p>{@link SmartInitializingSingleton} を実装するのは、全シングルトン Bean が生成された後で
 * 走査するためである。{@code @PostConstruct} だと自分自身を含む一部の Bean が未生成のまま参照され、
 * 走査もれが発生するため避ける。</p>
 */
@Slf4j
@Component
public class BatchEndpointRegistry implements SmartInitializingSingleton {

    private final ApplicationContext applicationContext;
    private final Map<String, BatchEndpointDescriptor> endpoints = new LinkedHashMap<>();

    /**
     * 全 Bean 走査を行うか。
     *
     * <p>この走査は {@code applicationContext.getBean(beanName)} を全 Bean 定義に対して呼ぶため、
     * {@code spring.main.lazy-initialization=true} を指定していても**コンテキスト全体を強制的に生成する**。
     * 通常起動では必要な処理だが、バッチを一度も実行しない用途（{@code openapi-gen} プロファイルでの
     * {@code generateOpenApiDocs}）では、この走査だけで起動が数十分伸びて意味がない
     * （CMP-260912-1526 の実測では起動 30 分のうち約 22 分がこの走査だった）。
     * そのため、走査を明示的に無効化できるようにしてある。既定は有効。</p>
     */
    private final boolean scanEnabled;

    public BatchEndpointRegistry(
            ApplicationContext applicationContext,
            @Value("${mannschaft.batch.registry.scan-enabled:true}") boolean scanEnabled) {
        this.applicationContext = applicationContext;
        this.scanEnabled = scanEnabled;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!scanEnabled) {
            log.info("バッチエンドポイント走査は無効化されています（mannschaft.batch.registry.scan-enabled=false）");
            return;
        }
        String[] beanNames = applicationContext.getBeanDefinitionNames();
        for (String beanName : beanNames) {
            Object bean;
            try {
                bean = applicationContext.getBean(beanName);
            } catch (Exception ex) {
                // 一部の Bean は遅延初期化やプロファイル不一致で取れないことがある。
                // 走査対象外として握り、レジストリの構築は継続する。
                log.debug("Bean を取得できなかったためスキップ: beanName={}, ex={}", beanName, ex.toString());
                continue;
            }
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            Map<Method, BatchEndpoint> annotatedMethods = MethodIntrospector.selectMethods(
                    targetClass,
                    (MethodIntrospector.MetadataLookup<BatchEndpoint>) method ->
                            AnnotatedElementUtils.findMergedAnnotation(method, BatchEndpoint.class));
            if (annotatedMethods.isEmpty()) {
                continue;
            }
            for (Map.Entry<Method, BatchEndpoint> entry : annotatedMethods.entrySet()) {
                Method method = entry.getKey();
                BatchEndpoint annotation = entry.getValue();
                String name = annotation.name();
                if (name == null || name.isBlank()) {
                    throw new IllegalStateException(
                            "@BatchEndpoint.name() が空です: " + targetClass.getName() + "#" + method.getName());
                }
                BatchEndpointDescriptor existing = endpoints.get(name);
                if (existing != null) {
                    throw new IllegalStateException(String.format(
                            "@BatchEndpoint name=\"%s\" が重複しています: %s#%s と %s#%s",
                            name,
                            existing.beanName(),
                            existing.method().getName(),
                            beanName,
                            method.getName()));
                }
                SchedulerLock lockAnnotation = AnnotatedElementUtils.findMergedAnnotation(method, SchedulerLock.class);
                String lockName = lockAnnotation != null ? lockAnnotation.name() : null;
                BatchEndpointDescriptor descriptor = new BatchEndpointDescriptor(
                        name, annotation.description(), beanName, method, lockName);
                endpoints.put(name, descriptor);
                log.info("バッチエンドポイント登録: name={}, bean={}, method={}#{}, lock={}",
                        name, beanName, targetClass.getSimpleName(), method.getName(), lockName);
            }
        }
        log.info("バッチエンドポイント走査完了: 登録数={}", endpoints.size());
    }

    /**
     * 名前でエンドポイントを検索する。
     *
     * @param name {@link BatchEndpoint#name()}
     * @return 該当ディスクリプタ
     */
    public Optional<BatchEndpointDescriptor> find(String name) {
        return Optional.ofNullable(endpoints.get(name));
    }

    /**
     * 登録済みエンドポイント一覧を返す（登録順）。
     *
     * @return ディスクリプタ一覧（不変ビュー）
     */
    public List<BatchEndpointDescriptor> listAll() {
        return Collections.unmodifiableList(new java.util.ArrayList<>(endpoints.values()));
    }

    /**
     * 名前で指定したバッチを実行する。
     *
     * <p>該当 Bean のプロキシ経由でメソッドを呼ぶことで {@code @Transactional} や
     * {@link BatchExecutionAspect} が正しく適用される。引数を持つメソッドはサポート外で、
     * 該当した場合は {@link IllegalStateException} を投げる（運用上、バッチは引数なしの想定）。</p>
     *
     * @param name {@link BatchEndpoint#name()}
     * @throws IllegalArgumentException 未登録の name
     * @throws IllegalStateException    引数を持つメソッドだった場合
     */
    public void invoke(String name) {
        BatchEndpointDescriptor descriptor = endpoints.get(name);
        if (descriptor == null) {
            throw new IllegalArgumentException("未登録のバッチエンドポイントです: name=" + name);
        }
        if (descriptor.method().getParameterCount() > 0) {
            throw new IllegalStateException(
                    "@BatchEndpoint メソッドは引数なしである必要があります: name=" + name);
        }
        Object bean = applicationContext.getBean(descriptor.beanName());
        try {
            Method method = ReflectionUtils.findMethod(bean.getClass(), descriptor.method().getName());
            if (method == null) {
                // プロキシ越しに見つからなければ素のターゲットメソッドを使う
                method = descriptor.method();
            }
            ReflectionUtils.makeAccessible(method);
            method.invoke(bean);
        } catch (RuntimeException ex) {
            // 業務例外はそのまま再投げ（BatchExecutionAspect が捕まえる）
            throw ex;
        } catch (Exception ex) {
            // checked 例外は IllegalStateException でラップ（@BatchEndpoint は checked 想定しない）
            throw new IllegalStateException("バッチ実行に失敗しました: name=" + name, ex);
        }
    }
}
