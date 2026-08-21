package com.mannschaft.app.common.backgroundgate;

import com.mannschaft.app.admin.service.FeatureFlagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link BackgroundFeaturePolicyAspect} の単体テスト
 * （Gate 基盤工事④-A・試練 / 受け入れ条件 AC-1〜AC-7）。
 *
 * <p><b>金型</b>: {@code FeatureGateAspectTest}
 * （{@link AspectJProxyFactory} で手動プロキシを構築し、Spring コンテキストを起動しない）。</p>
 *
 * <h2>なぜ CGLIB（クラスベース）プロキシで検証するのか</h2>
 * <p>Spring Boot は既定で {@code spring.aop.proxy-target-class=true} であり、
 * 実運用の AOP プロキシはクラスベースである。また {@code @Scheduled} /
 * {@code @EventListener} は具象 Bean のメソッドに付くのが常であり、
 * インターフェース越しに呼ばれることはない。よって本テストも実運用に合わせて
 * {@code setProxyTargetClass(true)} で検証する。</p>
 *
 * <h2>「例外を投げないこと」自体が要件である（AC-2 / AC-4）</h2>
 * <p>本番の {@code @TransactionalEventListener} は 143 件すべてが
 * {@code phase = AFTER_COMMIT} で、このフェーズで投げた例外は Spring が握り潰す
 * （ログにすら出ない）。またバッチで例外を投げると {@code BatchExecutionAspect} が
 * {@code batch_job_logs} に FAILED を書き {@code BatchFailedEvent} を飛ばし、
 * 意図した停止が障害として運用に通知される。したがって「拒否＝例外」は採れない。</p>
 *
 * <p>実行記録（AC-8〜AC-10）は {@code BackgroundFeatureSkipRecorderTest}、
 * 手動実行の拒否（AC-11）は {@code BackgroundFeatureManualTriggerRejectionTest}、
 * 宣言の静的検証（AC-12〜AC-15）は {@code BackgroundFeaturePolicyAnnotationGuardTest} が受け持つ。</p>
 */
@DisplayName("BackgroundFeaturePolicyAspect 単体テスト（Gate基盤工事④-A AC-1〜AC-7）")
class BackgroundFeaturePolicyAspectTest {

    private static final String FLAG_A = "FEATURE_SHIFT_ENABLED";
    private static final String FLAG_B = "FEATURE_MARKET_ENABLED";

    /** {@code feature_flags} に行が存在しないキー（isEnabled は orElse(false) で false を返す）。 */
    private static final String UNKNOWN_FLAG = "FEATURE_NO_SUCH_ROW_ENABLED";

    private FeatureFlagService featureFlagService;
    private BackgroundFeatureSkipRecorder skipRecorder;

    @BeforeEach
    void setUp() {
        featureFlagService = mock(FeatureFlagService.class);
        skipRecorder = mock(BackgroundFeatureSkipRecorder.class);
    }

    private <T> T proxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new BackgroundFeaturePolicyAspect(featureFlagService, skipRecorder));
        return factory.getProxy();
    }

    // ===============================================================
    // AC-1: SKIP_WHEN_DISABLED + フラグ有効 → 本体が実行される
    // ===============================================================

    @Test
    @DisplayName("(AC-1) SKIP_WHEN_DISABLED でフラグが有効なら本体が実行される")
    void ac1_スキップ宣言でもフラグ有効なら本体が実行される() {
        when(featureFlagService.isEnabled(FLAG_A)).thenReturn(true);

        SkipSample target = new SkipSample();
        SkipSample svc = proxy(target);

        int processed = svc.singleFlag();

        assertThat(target.invocations)
                .as("フラグが有効なのだから本体は実行されていなければならない")
                .isEqualTo(1);
        assertThat(processed)
                .as("本体の戻り値がそのまま返ること（Aspect が戻り値を握り潰していない）")
                .isEqualTo(SkipSample.PROCESSED);
        verify(featureFlagService).isEnabled(FLAG_A);
    }

    // ===============================================================
    // AC-2: SKIP_WHEN_DISABLED + フラグ無効 → 本体を呼ばず例外も投げない
    // ===============================================================

    @Test
    @DisplayName("(AC-2) SKIP_WHEN_DISABLED でフラグ無効なら本体を呼ばず、例外も投げずに正常終了する")
    void ac2_フラグ無効なら本体を呼ばず例外も投げない() {
        when(featureFlagService.isEnabled(FLAG_A)).thenReturn(false);

        SkipSample target = new SkipSample();
        SkipSample svc = proxy(target);

        assertThatCode(svc::singleFlag)
                .as("バッチで例外を投げると BatchExecutionAspect が batch_job_logs に FAILED を書き "
                        + "BatchFailedEvent を飛ばすため、意図した停止が障害として運用に通知される。"
                        + "「例外を投げないこと」自体が受け入れ条件である")
                .doesNotThrowAnyException();

        assertThat(target.invocations)
                .as("フラグ無効なのだから本体は1度も実行されていないこと")
                .isZero();
    }

    @Test
    @DisplayName("(AC-2b) SKIP_WHEN_DISABLED のスキップ時は戻り値型の既定値（int なら 0）を返す")
    void ac2b_スキップ時は戻り値型の既定値を返す() {
        when(featureFlagService.isEnabled(FLAG_A)).thenReturn(false);

        SkipSample svc = proxy(new SkipSample());

        assertThat(svc.singleFlag())
                .as("処理件数 0 件として返ること。BatchExecutionAspect は int/long の戻り値を "
                        + "processedCount に採用するため、既定値以外を返すと実績が捏造される")
                .isZero();
    }

    @Test
    @DisplayName("(AC-2c) 戻り値 void のスキップでも例外を投げずに正常終了する")
    void ac2c_void戻り値のスキップでも例外を投げない() {
        when(featureFlagService.isEnabled(FLAG_A)).thenReturn(false);

        SkipSample target = new SkipSample();
        SkipSample svc = proxy(target);

        assertThatCode(svc::voidFlag).doesNotThrowAnyException();
        assertThat(target.voidInvocations).isZero();
    }

    // ===============================================================
    // AC-3: ALWAYS → フラグが無効でも実行、isEnabled を呼びすらしない
    // ===============================================================

    @Test
    @DisplayName("(AC-3) ALWAYS はフラグ判定を一切行わず必ず本体を実行する")
    void ac3_ALWAYSは判定せず必ず実行される() {
        // 全フラグ無効に倒しておく（mock の既定も false）。それでも実行されねばならない。
        when(featureFlagService.isEnabled(anyString())).thenReturn(false);

        AlwaysSample target = new AlwaysSample();
        AlwaysSample svc = proxy(target);

        assertThat(svc.gdprPurge()).isEqualTo(AlwaysSample.PROCESSED);
        assertThat(target.invocations)
                .as("ALWAYS は GDPR 削除・監査・課金整合など「止めると既存データの整合性が壊れる」"
                        + "処理に付く宣言であり、フラグの状態に関わらず実行されねばならない")
                .isEqualTo(1);

        verify(featureFlagService, never()).isEnabled(anyString());
    }

    // ===============================================================
    // AC-4: DROP_WHEN_DISABLED + フラグ無効 → 本体を呼ばず例外も投げない
    // ===============================================================

    @Test
    @DisplayName("(AC-4) DROP_WHEN_DISABLED でフラグ無効ならイベントを捨て、例外を投げない")
    void ac4_ドロップ時は本体を呼ばず例外も投げない() {
        when(featureFlagService.isEnabled(FLAG_A)).thenReturn(false);

        DropSample target = new DropSample();
        DropSample svc = proxy(target);

        assertThatCode(() -> svc.onSomething("payload"))
                .as("@TransactionalEventListener(phase = AFTER_COMMIT) で投げた例外は Spring が"
                        + "握り潰しログにも出ない。拒否を例外で表現すると「拒否したつもりが黙って通っている」"
                        + "事故と区別が付かなくなるため、構造的に例外を出さないことが要件である")
                .doesNotThrowAnyException();

        assertThat(target.invocations)
                .as("フラグ無効なのだからリスナー本体は1度も実行されていないこと")
                .isZero();
    }

    @Test
    @DisplayName("(AC-4b) DROP_WHEN_DISABLED でもフラグ有効ならリスナー本体は実行される")
    void ac4b_フラグ有効ならリスナー本体は実行される() {
        when(featureFlagService.isEnabled(FLAG_A)).thenReturn(true);

        DropSample target = new DropSample();
        DropSample svc = proxy(target);

        svc.onSomething("payload");

        assertThat(target.invocations).isEqualTo(1);
    }

    // ===============================================================
    // AC-5: seed 済みでない未知キーはフェイルクローズ
    // ===============================================================

    @Test
    @DisplayName("(AC-5) feature_flags に行が無い未知キーはフェイルクローズ（無効扱い）でスキップされる")
    void ac5_未知キーはフェイルクローズでスキップされる() {
        // FeatureFlagService.isEnabled は行が無ければ orElse(false) で false を返す。
        when(featureFlagService.isEnabled(UNKNOWN_FLAG)).thenReturn(false);

        UnknownKeySample target = new UnknownKeySample();
        UnknownKeySample svc = proxy(target);

        assertThatCode(svc::unknownFlag).doesNotThrowAnyException();

        assertThat(target.invocations)
                .as("未知キーを「判定不能だから通す」と扱うと、綴り間違いが"
                        + "「ゲートしたつもりで動き続ける」穴になる。無効扱い（フェイルクローズ）が正")
                .isZero();
    }

    @Test
    @DisplayName("(AC-5b) DROP_WHEN_DISABLED でも未知キーはフェイルクローズでドロップされる")
    void ac5b_ドロップ側でも未知キーはフェイルクローズされる() {
        when(featureFlagService.isEnabled(UNKNOWN_FLAG)).thenReturn(false);

        UnknownKeyDropSample target = new UnknownKeyDropSample();
        UnknownKeyDropSample svc = proxy(target);

        assertThatCode(() -> svc.onSomething("payload")).doesNotThrowAnyException();
        assertThat(target.invocations).isZero();
    }

    // ===============================================================
    // AC-6: 複数キーは AND（1つでも無効ならスキップ／ドロップ）
    // ===============================================================

    @Test
    @DisplayName("(AC-6a) 複数キーは AND — 全て有効なときだけ本体が実行される")
    void ac6a_全て有効なら実行される() {
        when(featureFlagService.isEnabled(FLAG_A)).thenReturn(true);
        when(featureFlagService.isEnabled(FLAG_B)).thenReturn(true);

        MultiKeySample target = new MultiKeySample();
        MultiKeySample svc = proxy(target);

        assertThat(svc.multi()).isEqualTo(MultiKeySample.PROCESSED);
        assertThat(target.invocations).isEqualTo(1);
    }

    @Test
    @DisplayName("(AC-6b) 複数キーは AND — 1つ目だけ無効でもスキップされる")
    void ac6b_1つ目が無効ならスキップされる() {
        when(featureFlagService.isEnabled(FLAG_A)).thenReturn(false);
        when(featureFlagService.isEnabled(FLAG_B)).thenReturn(true);

        MultiKeySample target = new MultiKeySample();
        MultiKeySample svc = proxy(target);

        assertThatCode(svc::multi).doesNotThrowAnyException();
        assertThat(target.invocations).isZero();
    }

    @Test
    @DisplayName("(AC-6c) 複数キーは AND — 2つ目だけ無効でもスキップされる（境界: 片方だけ無効）")
    void ac6c_2つ目が無効ならスキップされる() {
        when(featureFlagService.isEnabled(FLAG_A)).thenReturn(true);
        when(featureFlagService.isEnabled(FLAG_B)).thenReturn(false);

        MultiKeySample target = new MultiKeySample();
        MultiKeySample svc = proxy(target);

        assertThatCode(svc::multi).doesNotThrowAnyException();
        assertThat(target.invocations)
                .as("OR で実装されていると 1 つ目が有効な時点で通ってしまい、この検体だけが落ちる")
                .isZero();
    }

    // ===============================================================
    // AC-7: 未付与メソッドでは Aspect が動かない
    // ===============================================================

    @Test
    @DisplayName("(AC-7) @BackgroundFeaturePolicy 未付与のメソッドでは isEnabled を一度も呼ばない")
    void ac7_未付与メソッドではAspectが動かない() {
        NoPolicySample target = new NoPolicySample();
        NoPolicySample svc = proxy(target);

        assertThat(svc.plain()).isEqualTo(NoPolicySample.PROCESSED);
        assertThat(target.invocations).isEqualTo(1);

        verifyNoInteractions(featureFlagService);
        verifyNoInteractions(skipRecorder);
    }

    @Test
    @DisplayName("(AC-7b) 同一クラス内で未付与メソッドは、付与メソッドの宣言に巻き込まれない")
    void ac7b_同一クラスの未付与メソッドは巻き込まれない() {
        when(featureFlagService.isEnabled(FLAG_A)).thenReturn(false);

        SkipSample target = new SkipSample();
        SkipSample svc = proxy(target);

        // 付与メソッドはスキップされるが……
        svc.singleFlag();
        assertThat(target.invocations).isZero();

        // 同じクラスの未付与メソッドは素通しされること（クラスレベル適用になっていない）。
        assertThat(svc.notAnnotated()).isEqualTo(SkipSample.PROCESSED);
        assertThat(target.notAnnotatedInvocations).isEqualTo(1);
    }

    // ===============================================================
    // 検体（付与位置がメソッドのみであることに注意）
    // ===============================================================

    /** {@code @Scheduled} + SKIP_WHEN_DISABLED の検体。 */
    static class SkipSample {
        static final int PROCESSED = 7;
        int invocations;
        int voidInvocations;
        int notAnnotatedInvocations;

        @Scheduled(cron = "0 0 3 * * *")
        @BackgroundFeaturePolicy(
                mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
                gateKeys = FLAG_A,
                reason = "シフト機能はβ非公開のため、無効中は集計を走らせない。停止しても既存データの整合性は壊れない。")
        public int singleFlag() {
            invocations++;
            return PROCESSED;
        }

        @Scheduled(cron = "0 0 4 * * *")
        @BackgroundFeaturePolicy(
                mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
                gateKeys = FLAG_A,
                reason = "戻り値 void のバッチでもスキップが成立することを確かめるための検体である。")
        public void voidFlag() {
            voidInvocations++;
        }

        /** 宣言を付けていないメソッド（AC-7b 用）。 */
        public int notAnnotated() {
            notAnnotatedInvocations++;
            return PROCESSED;
        }
    }

    /** ALWAYS の検体。 */
    static class AlwaysSample {
        static final int PROCESSED = 3;
        int invocations;

        @Scheduled(cron = "0 0 5 * * *")
        @BackgroundFeaturePolicy(
                mode = BackgroundFeatureMode.ALWAYS,
                reason = "GDPR 削除要求の消化は法令上の義務であり、フラグの状態に関わらず必ず実行する。")
        public int gdprPurge() {
            invocations++;
            return PROCESSED;
        }
    }

    /** {@code @EventListener} + DROP_WHEN_DISABLED の検体。 */
    static class DropSample {
        int invocations;

        @EventListener
        @BackgroundFeaturePolicy(
                mode = BackgroundFeatureMode.DROP_WHEN_DISABLED,
                gateKeys = FLAG_A,
                reason = "シフト機能β非公開中の通知イベントは再生されず失われるが、通知は補助的で欠落しても整合性は保たれる。")
        public void onSomething(String payload) {
            invocations++;
        }
    }

    /** seed に無いキーを指定した検体（AC-5 用。番人はテストソースを走査しないため成立する）。 */
    static class UnknownKeySample {
        int invocations;

        @Scheduled(cron = "0 0 6 * * *")
        @BackgroundFeaturePolicy(
                mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
                gateKeys = UNKNOWN_FLAG,
                reason = "フェイルクローズの実測用に、feature_flags へ行を持たないキーを意図的に指定した検体である。")
        public int unknownFlag() {
            invocations++;
            return 1;
        }
    }

    /** seed に無いキー × DROP の検体（AC-5b 用）。 */
    static class UnknownKeyDropSample {
        int invocations;

        @EventListener
        @BackgroundFeaturePolicy(
                mode = BackgroundFeatureMode.DROP_WHEN_DISABLED,
                gateKeys = UNKNOWN_FLAG,
                reason = "フェイルクローズの実測用に、feature_flags へ行を持たないキーを意図的に指定した検体である。")
        public void onSomething(String payload) {
            invocations++;
        }
    }

    /** 複数キー（AND）の検体。 */
    static class MultiKeySample {
        static final int PROCESSED = 11;
        int invocations;

        @Scheduled(cron = "0 0 7 * * *")
        @BackgroundFeaturePolicy(
                mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
                gateKeys = {FLAG_A, FLAG_B},
                reason = "シフトとマーケットの双方が公開されて初めて意味を持つ集計であり、片方でも無効なら走らせない。")
        public int multi() {
            invocations++;
            return PROCESSED;
        }
    }

    /** 宣言を一切持たない検体（AC-7 用）。 */
    static class NoPolicySample {
        static final int PROCESSED = 5;
        int invocations;

        @Scheduled(cron = "0 0 8 * * *")
        public int plain() {
            invocations++;
            return PROCESSED;
        }
    }
}
