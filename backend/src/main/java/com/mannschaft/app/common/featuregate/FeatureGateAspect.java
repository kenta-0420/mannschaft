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
 * <p>Spring AOP の pointcut マッチング（{@code @annotation()}・{@code @within()}含む）は
 * 内部で {@code ClassUtils.getMostSpecificMethod(method, targetClass)} により<b>実装クラス側の
 * オーバーライドメソッド</b>へ解決してから注釈の有無を判定する。Java の注釈はインターフェースから
 * オーバーライドメソッドへ継承されないため、{@code @RequireFeature} をインターフェース
 * （型またはメソッド）にのみ付与すると、<b>JDK 動的プロキシ・CGLIB のどちらでも pointcut が
 * 一致せず本 Aspect が一切発火しない</b>（{@code FeatureGateAspectTest} の AC-13a〜d で
 * 実測固定済み）。これは pointcut 表現をどう書き足しても構造上回避できない
 * （{@code execution()} を足しても同じ理由で一致しない）。</p>
 * <p>したがって本 Aspect 側にこの迂回への対策コードを持たせることはできない。
 * 唯一の防御は {@code RequireFeatureInterfaceGuardTest}（インターフェースへの付与自体を
 * CI で拒否する）であり、<b>{@code @RequireFeature} は実装クラス（またはその public
 * メソッド）にのみ付与すること</b>。</p>
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
