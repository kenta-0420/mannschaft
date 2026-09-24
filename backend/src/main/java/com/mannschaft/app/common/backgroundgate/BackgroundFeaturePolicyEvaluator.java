package com.mannschaft.app.common.backgroundgate;

import com.mannschaft.app.admin.service.FeatureFlagService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * 管理画面からの<b>手動実行</b>に対して {@link BackgroundFeaturePolicy} を評価する（Gate 基盤工事④-A）。
 *
 * <h2>なぜ手動実行だけ別扱いなのか</h2>
 * <p>{@code SKIP_WHEN_DISABLED} のスキップは「黙って正常終了」が正しい（スケジューラに対して
 * 障害を報告しないため）。しかし管理画面の
 * {@code POST /api/v1/system-admin/batch/{name}/trigger} で同じ挙動をすると、
 * 操作した人間には <b>202 Accepted が返り、実際には何も動いていない</b>という
 * 最悪の見え方になる。人間が起動した以上、拒否は<b>明示的に</b>返さねばならない。</p>
 *
 * <p>判定結果は {@code SystemAdminBatchController#trigger} が 409 Conflict
 * （{@code status="FEATURE_DISABLED"}）として返す。403 は認可の失敗と読まれ、
 * SYSTEM_ADMIN が自分の権限を疑い始めるため採らない。</p>
 */
@Component
@RequiredArgsConstructor
public class BackgroundFeaturePolicyEvaluator {

    private final FeatureFlagService featureFlagService;

    /**
     * 手動実行を拒否すべきかを判定する。
     *
     * @param method 起動対象のバッチメソッド
     * @return 拒否理由（空なら実行してよい）
     */
    public Optional<String> manualExecutionRejection(Method method) {
        BackgroundFeaturePolicy policy =
                AnnotationUtils.findAnnotation(method, BackgroundFeaturePolicy.class);

        // 宣言が無いバッチ（④-D で必須になるまでは多数存在する）は従来どおり許可する。
        if (policy == null || policy.mode() == BackgroundFeatureMode.ALWAYS) {
            // ALWAYS は判定そのものを行わない（フラグに関わらず必ず実行してよい）。
            return Optional.empty();
        }

        // gateKeys は AND。1 つでも無効なら、そのキーを名指しして拒否する。
        for (String key : policy.gateKeys()) {
            // 行が無い未知キーは isEnabled が false を返す＝フェイルクローズ。
            if (!featureFlagService.isEnabled(key)) {
                return Optional.of(
                        "フィーチャーフラグ " + key + " が無効のため実行できません。"
                                + "実行するには管理コンソールで当該フラグを有効化してください。");
            }
        }
        return Optional.empty();
    }
}
