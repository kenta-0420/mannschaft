package com.mannschaft.app.common.backgroundgate;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.admin.service.FeatureFlagService;
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

import java.lang.reflect.Array;
import java.lang.reflect.Method;

/**
 * {@link BackgroundFeaturePolicy} 付きメソッドの停止時挙動を実行する AOP（Gate 基盤工事④-A）。
 *
 * <h2>{@code @Order(5)} の理由</h2>
 * <p>{@code BatchExecutionAspect} は {@code @Order} 未指定＝{@code LOWEST_PRECEDENCE} であり、
 * {@code @BatchEndpoint} 付きメソッドを包んで {@code batch_job_logs} に RUNNING を書く。
 * 本 Aspect がその<b>内側</b>に居ると、スキップのたびに RUNNING→COMPLETED の
 * 空回りログが積まれ、受け入れ条件 AC-9（連続スキップ中は記録を積まない）が構造的に守れない。
 * よって {@code FeatureGateAspect}（{@code @Order(10)}）よりさらに外側の
 * {@code @Order(5)} に置き、スキップ判定を最外周で確定させる。</p>
 *
 * <h2>拒否を例外で表現してはならない</h2>
 * <p>本番の {@code @TransactionalEventListener} は 143 件すべてが {@code phase = AFTER_COMMIT} で、
 * このフェーズで投げた例外は Spring が握り潰す（ログにすら出ない）。またバッチで例外を投げると
 * {@code BatchExecutionAspect} が FAILED を記録し {@code BatchFailedEvent} を飛ばすため、
 * 意図した停止が障害として運用に通知される。既存のフラグ判定 4 箇所も全て
 * 「黙ってスキップ」流儀であり、本 Aspect もそれに揃える。</p>
 *
 * <h2>記録の失敗はバッチの障害にしない</h2>
 * <p>{@link BackgroundFeatureSkipRecorder} の呼び出しは捕捉する。記録は停止判断に付随する
 * 情報であり、その失敗を再スローすると {@code BatchExecutionAspect} が FAILED を書き
 * {@code BatchFailedEvent} を飛ばすため、記録できなかっただけの回が「障害」として運用に
 * 通知される。捕捉した例外は握り潰さずスタックトレース付きで WARN に残す。</p>
 *
 * <h2>スキップ時の戻り値</h2>
 * <p>戻り値型の<b>既定値</b>（{@code int} なら 0、参照型なら null、{@code void} なら null）を返す。
 * {@code BatchExecutionAspect} は {@code int}/{@code long} の戻り値を {@code processedCount} に
 * 採用するため、既定値以外を返すと「走っていないのに件数が立つ」＝実績の捏造になる。</p>
 */
@Aspect
@Component
@Order(5)
@RequiredArgsConstructor
@Slf4j
public class BackgroundFeaturePolicyAspect {

    private final FeatureFlagService featureFlagService;

    private final BackgroundFeatureSkipRecorder skipRecorder;

    /**
     * 宣言に従いフラグを評価し、無効ならば本体を呼ばずに戻る。
     *
     * @param pjp 結合点
     * @return 本体の戻り値、またはスキップ／ドロップ時は戻り値型の既定値
     * @throws Throwable 本体が投げた例外（Aspect 自身は例外を投げない）
     */
    @Around("@annotation(com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy)")
    public Object applyPolicy(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method targetMethod = resolveTargetMethod(pjp, signature);

        BackgroundFeaturePolicy policy =
                AnnotationUtils.findAnnotation(targetMethod, BackgroundFeaturePolicy.class);

        // 宣言が解決できない場合は素通しする（pointcut と解決経路の齟齬で本体を握り潰さない）。
        if (policy == null || policy.mode() == BackgroundFeatureMode.ALWAYS) {
            // ALWAYS は判定そのものを行わない。isEnabled を一度も呼ばないことが AC-3 の要件。
            return pjp.proceed();
        }

        // gateKeys は AND 評価。1 つでも無効なら停止する（最初に無効だったキーを理由に載せる）。
        String disabledKey = firstDisabledKey(policy.gateKeys());
        boolean skipped = disabledKey != null;

        if (policy.mode() == BackgroundFeatureMode.SKIP_WHEN_DISABLED) {
            // スキップ／実行の状態が変わった時だけ batch_job_logs に 1 行残す（AC-8〜AC-10）。
            //
            // BackgroundFeatureSkipRecorder 自身も記録失敗を隔離するが、AC-2 の「例外を投げずに
            // 正常終了」という保証を協力者の行儀に依存させない（記録は付随的な情報であり、
            // その失敗が停止判断そのものを障害に変えてはならない）。ここで再スローすると
            // BatchExecutionAspect が batch_job_logs に FAILED を書き BatchFailedEvent を飛ばすため、
            // 「意図した停止」が「障害」として運用に通知される。捕捉が正当なのはこの理由による。
            // 握り潰しではない: 捕捉した例外はスタックトレース付きで WARN に残す。
            try {
                skipRecorder.recordIfStateChanged(
                        jobNameOf(targetMethod),
                        skipped,
                        skipped ? disabledKey + " が無効のためスキップしました" : null);
            } catch (RuntimeException ex) {
                log.warn("BackgroundFeaturePolicy の状態遷移記録が例外で失敗した"
                        + "（記録のみ失敗。停止判断は継続する）: method={}, skipped={}",
                        targetMethod.getName(), skipped, ex);
            }
        }

        if (!skipped) {
            return pjp.proceed();
        }

        if (log.isDebugEnabled()) {
            log.debug("BackgroundFeaturePolicyAspect: 停止 mode={}, method={}, key={}",
                    policy.mode(), targetMethod.getName(), disabledKey);
        }
        return defaultValueOf(signature.getReturnType());
    }

    /**
     * 実装クラス側の対応メソッドを解決する。
     *
     * <p>JDK 動的プロキシではインターフェースのメソッドが渡り注釈が取れないため、
     * {@code FeatureGateAspect} と同じく {@code getMostSpecificMethod} を通す。</p>
     */
    private Method resolveTargetMethod(ProceedingJoinPoint pjp, MethodSignature signature) {
        Object target = pjp.getTarget();
        if (target == null) {
            return signature.getMethod();
        }
        return AopUtils.getMostSpecificMethod(signature.getMethod(), target.getClass());
    }

    /**
     * gateKeys を AND 評価し、最初に無効だったキーを返す。
     *
     * @return 無効だったキー。すべて有効なら {@code null}
     */
    private String firstDisabledKey(String[] gateKeys) {
        for (String key : gateKeys) {
            // 行が無い未知キーは isEnabled が false を返す＝フェイルクローズ（AC-5）。
            if (!featureFlagService.isEnabled(key)) {
                return key;
            }
        }
        return null;
    }

    /**
     * 記録に用いるジョブ識別子。{@code @BatchEndpoint} があればその name、無ければ FQCN#メソッド名。
     */
    private String jobNameOf(Method method) {
        BatchEndpoint batchEndpoint = AnnotationUtils.findAnnotation(method, BatchEndpoint.class);
        if (batchEndpoint != null && !batchEndpoint.name().isBlank()) {
            return batchEndpoint.name();
        }
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }

    /**
     * 戻り値型の既定値。
     *
     * <p>プリミティブは長さ 1 の配列を作って 0 番目を読むことで、型ごとの分岐を書かずに
     * 言語仕様上の既定値（0 / 0L / false / '\u0000' 等）を得る。</p>
     */
    private Object defaultValueOf(Class<?> returnType) {
        if (returnType == null || returnType == void.class || returnType == Void.class
                || !returnType.isPrimitive()) {
            return null;
        }
        return Array.get(Array.newInstance(returnType, 1), 0);
    }
}
