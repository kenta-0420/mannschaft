package com.mannschaft.app.common.featuregate;

import com.mannschaft.app.admin.service.FeatureFlagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@link RequireFeature} 付きメソッドに対する事前ガード AOP（Gate 基盤工事③）。
 *
 * <p>{@code @Order(10)} を指定し、Spring の {@code @Transactional}
 * （既定 {@code Ordered.LOWEST_PRECEDENCE}）より十分先に走らせる。
 * これによりトランザクション開始前に拒否が確定し、拒否時に DB 書き込みが一切発生しない
 * （金型: {@code RepairPlanModuleGuardAspect}）。</p>
 *
 * <p>⚠️⚠️ <b>試練の骨格である。本アドバイスは意図的に「何もしない」</b>。
 * これにより受け入れ条件 AC-2〜AC-4・AC-10 の試練が red になる。
 * 出陣で以下を実装すること（本コメントごと清掃する）:</p>
 * <ol>
 *   <li>メソッドレベル → クラスレベルの順で {@link RequireFeature} を解決する
 *       （メソッドレベルがクラスレベルより優先。AC-4）</li>
 *   <li>{@link FeatureFlagService#isEnabled(String)} を {@code value()} の全キーに対して評価し、
 *       1 つでも false なら {@code BusinessException(FEATURE_GATE_001)} を投げる（AND 判定。AC-10）</li>
 *   <li>行が無い未知キーは {@code isEnabled} が false を返すためフェイルクローズになる（AC-3）</li>
 * </ol>
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
        // 試練の骨格: 何もせず素通しする。出陣でフラグ判定を実装する。
        return pjp.proceed();
    }
}
