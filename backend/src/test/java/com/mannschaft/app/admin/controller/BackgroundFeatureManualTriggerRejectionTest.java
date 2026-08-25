package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.service.FeatureFlagService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicyEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Gate 基盤工事④-A / 受け入れ条件 AC-11（判定層） — 管理画面からの<b>手動実行</b>に対し、
 * {@link BackgroundFeaturePolicyEvaluator} がフラグ無効なら拒否理由を返すこと。
 *
 * <h2>なぜ手動実行だけ挙動を変えるのか</h2>
 * <p>{@code SKIP_WHEN_DISABLED} のスケジュール実行は「黙って正常終了」が正しい
 * （AC-2。例外を投げると {@code BatchExecutionAspect} が FAILED を書き
 * {@code BatchFailedEvent} を飛ばし、意図した停止が障害として運用に通知されるため）。</p>
 *
 * <p>しかし同じ挙動を {@code POST /api/v1/system-admin/batch/{name}/trigger} に適用すると、
 * ボタンを押した人間には <b>202 Accepted が返り、実際には何も動いていない</b>という
 * 最悪の見え方になる。押した人は「走った」と信じ、結果が出ないことを別の障害として調べ始める。
 * 人間が明示的に起動した以上、拒否は明示的に返さねばならない。</p>
 *
 * <p>AC-11 は 2 層で固定する。本クラスが<b>判定そのもの</b>を、
 * {@code BackgroundFeatureManualTriggerEndpointTest} が
 * <b>その判定が実際に HTTP 応答へ効いていること</b>を見る。
 * 評価器だけを直しても Controller から呼ばれていなければ穴は開いたままであり、
 * 逆もまた同じであるため、両方を独立に落とす。</p>
 */
@DisplayName("Gate基盤工事④-A: 手動実行の拒否判定（AC-11 判定層）")
class BackgroundFeatureManualTriggerRejectionTest {

    static final String GATED_FLAG = "FEATURE_SHIFT_ENABLED";

    /**
     * 検体となるバッチ Bean。{@code BatchEndpointDescriptor#method()} に渡す
     * 実 {@link Method} をここから取り出す。
     */
    static class GatedBatch {

        @Scheduled(cron = "0 0 3 * * *")
        @BackgroundFeaturePolicy(
                mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
                gateKeys = GATED_FLAG,
                reason = "シフト機能はβ非公開のため、無効中はバッチを走らせない。停止しても既存データの整合性は壊れない。")
        public int gatedJob() {
            return 1;
        }

        @Scheduled(cron = "0 0 4 * * *")
        @BackgroundFeaturePolicy(
                mode = BackgroundFeatureMode.ALWAYS,
                reason = "GDPR 削除要求の消化は法令上の義務であり、フラグの状態に関わらず必ず実行する。")
        public int alwaysJob() {
            return 1;
        }

        /** 宣言を持たない既存バッチ（④-D で宣言必須になるまでは多数存在する）。 */
        @Scheduled(cron = "0 0 5 * * *")
        public int undeclaredJob() {
            return 1;
        }
    }

    static Method method(String name) throws Exception {
        return GatedBatch.class.getMethod(name);
    }

    private FeatureFlagService featureFlagService;
    private BackgroundFeaturePolicyEvaluator evaluator;

    @BeforeEach
    void setUp() {
        featureFlagService = mock(FeatureFlagService.class);
        evaluator = new BackgroundFeaturePolicyEvaluator(featureFlagService);
    }

    @Test
    @DisplayName("(AC-11a) フラグ無効なら拒否理由を返す")
    void ac11a_フラグ無効なら拒否理由を返す() throws Exception {
        given(featureFlagService.isEnabled(GATED_FLAG)).willReturn(false);

        Optional<String> rejection = evaluator.manualExecutionRejection(method("gatedJob"));

        assertThat(rejection)
                .as("フラグ無効のバッチを人間が手動起動したなら、拒否理由が返らねばならない。"
                        + "空を返すと Controller は「実行してよい」と解釈し 202 を返してしまう")
                .isPresent();
        assertThat(rejection.orElseThrow())
                .as("どのフラグが無効なのかが分からないと、管理者は ON に戻す先を特定できない")
                .contains(GATED_FLAG);
    }

    @Test
    @DisplayName("(AC-11b) フラグ有効なら拒否しない（偽陽性が無い）")
    void ac11b_フラグ有効なら拒否しない() throws Exception {
        given(featureFlagService.isEnabled(GATED_FLAG)).willReturn(true);

        assertThat(evaluator.manualExecutionRejection(method("gatedJob"))).isEmpty();
    }

    @Test
    @DisplayName("(AC-11c) ALWAYS 宣言はフラグ判定を行わず拒否しない")
    void ac11c_ALWAYSは拒否しない() throws Exception {
        assertThat(evaluator.manualExecutionRejection(method("alwaysJob"))).isEmpty();
        verify(featureFlagService, never()).isEnabled(any());
    }

    @Test
    @DisplayName("(AC-11d) 宣言を持たないバッチは従来どおり拒否しない")
    void ac11d_未宣言バッチは拒否しない() throws Exception {
        assertThat(evaluator.manualExecutionRejection(method("undeclaredJob"))).isEmpty();
        verify(featureFlagService, never()).isEnabled(any());
    }
}
