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
 */
@Aspect
@Component
@Order(10)
@RequiredArgsConstructor
@Slf4j
public class FeatureGateAspect {

    private final FeatureFlagService featureFlagService;

    @Around("@annotation(com.mannschaft.app.common.featuregate.RequireFeature)"
            + " || @within(com.mannschaft.app.common.featuregate.RequireFeature)")
    public Object gate(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        // JDK プロキシではインターフェースのメソッドが渡るためアノテーションが取れない。
        // 実装クラスの対応メソッドを解決してから探す。
        Method targetMethod = AopUtils.getMostSpecificMethod(method, pjp.getTarget().getClass());

        // メソッドレベル → クラスレベルの順でアノテーションを探す（メソッドレベルが優先）。
        RequireFeature annotation = AnnotationUtils.findAnnotation(targetMethod, RequireFeature.class);
        if (annotation == null) {
            annotation = AnnotationUtils.findAnnotation(pjp.getTarget().getClass(), RequireFeature.class);
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
