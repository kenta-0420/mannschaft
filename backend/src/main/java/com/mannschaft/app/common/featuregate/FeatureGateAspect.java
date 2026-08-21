package com.mannschaft.app.common.featuregate;

import com.mannschaft.app.admin.service.FeatureFlagService;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * {@link RequireFeature} 付きメソッドに対する事前ガード AOP（Gate 基盤工事③）。
 *
 * <p>{@code @Order(10)} を指定し、Spring の {@code @Transactional}
 * （既定 {@code Ordered.LOWEST_PRECEDENCE}）より十分先に走らせる。
 * これによりトランザクション開始前に拒否が確定し、拒否時に DB 書き込みが一切発生しない
 * （金型: {@code RepairPlanModuleGuardAspect}）。</p>
 *
 * <h2>{@code @RequireFeature} をインターフェースへ付与してはならない（Codex 検分指摘①）</h2>
 * <p>pointcut は {@code @annotation()}・{@code @within()}・
 * {@code execution(@RequireFeature * *(..))} を併用しているが、Spring AOP の pointcut
 * マッチングは内部で {@code ClassUtils.getMostSpecificMethod(method, targetClass)}
 * により<b>実装クラス側のオーバーライドメソッド</b>へ解決してから注釈の有無を判定する。
 * Java の注釈はインターフェースからオーバーライドメソッドへ継承されないため、
 * {@code @RequireFeature} をインターフェース（型またはメソッド）にのみ付与すると、
 * <b>JDK 動的プロキシ・CGLIB のどちらでも pointcut が一致せず本 Aspect が一切発火しない</b>
 * （{@code FeatureGateAspectTest} の AC-13a〜d で実測固定済み）。</p>
 * <p>下記のインターフェース側メソッド／宣言クラスへのアノテーション解決フォールバックは
 * 防御多層化として残しているが、<b>pointcut 自体が発火しない以上、この解決コードには
 * 到達しない</b>。したがってこの迂回を根治する唯一の手段は
 * {@code RequireFeatureInterfaceGuardTest}（インターフェースへの付与自体を CI で拒否する）
 * であり、実装クラス（またはその public メソッド）にのみ付与すること。</p>
 */
@Aspect
@Component
@Order(10)
@RequiredArgsConstructor
@Slf4j
public class FeatureGateAspect {

    private final FeatureFlagService featureFlagService;

    @Around("@annotation(com.mannschaft.app.common.featuregate.RequireFeature)"
            + " || @within(com.mannschaft.app.common.featuregate.RequireFeature)"
            + " || execution(@com.mannschaft.app.common.featuregate.RequireFeature * *(..))")
    public Object gate(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        // JDK プロキシではインターフェースのメソッドが渡るためアノテーションが取れない。
        // 実装クラスの対応メソッドを解決してから探す。
        Method targetMethod = AopUtils.getMostSpecificMethod(method, pjp.getTarget().getClass());

        // メソッドレベル → クラスレベルの順でアノテーションを探す（メソッドレベルが優先）。
        // targetMethod（実装クラス側）で見つからない場合に備え、signature 側のメソッド
        // （インターフェースにのみ付与されたケースを含む）とその宣言クラスからも解決を試みる
        // （防御多層化）。ただし本 Aspect のクラス Javadoc に記載の通り、インターフェースにのみ
        // 付与された場合は pointcut 自体が一致せずこの advice が発火しないため、実際にはこの
        // フォールバックへ到達しない。インターフェース付与は RequireFeatureInterfaceGuardTest
        // が CI で拒否する。
        RequireFeature annotation = AnnotationUtils.findAnnotation(targetMethod, RequireFeature.class);
        if (annotation == null) {
            annotation = AnnotationUtils.findAnnotation(pjp.getTarget().getClass(), RequireFeature.class);
        }
        if (annotation == null) {
            annotation = AnnotationUtils.findAnnotation(method, RequireFeature.class);
        }
        if (annotation == null) {
            annotation = AnnotationUtils.findAnnotation(method.getDeclaringClass(), RequireFeature.class);
        }

        if (annotation != null) {
            for (String key : annotation.value()) {
                // 行が無い未知キーは isEnabled が false を返す＝フェイルクローズ。
                if (!featureFlagService.isEnabled(key)) {
                    if (log.isDebugEnabled()) {
                        log.debug("FeatureGateAspect: 拒否 method={}, key={}", method.getName(), key);
                    }
                    throw new BusinessException(FeatureGateErrorCode.FEATURE_GATE_001);
                }
            }
        }

        return pjp.proceed();
    }
}
