package com.mannschaft.app.common.backgroundgate;

import com.mannschaft.app.admin.service.BatchJobLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * {@link BackgroundFeatureSkipRecorder} の単体テスト
 * （Gate 基盤工事④-A・試練 / 受け入れ条件 AC-8〜AC-10）。
 *
 * <h2>マスター裁可: 「状態が変わった時だけ記録する」</h2>
 * <p>毎分走るバッチをβ期間中ずっと無効にしておくと、素朴に毎回記録した場合
 * {@code batch_job_logs} がスキップ行で埋まり、本当に見たい実行履歴が読めなくなる。
 * よって<b>初回・および直前の結果と変わった時にだけ</b> 1 行記録する。
 * 「今スキップ中である」ことは直近 1 行を見れば分かるため、情報は失われない。</p>
 *
 * <p>これは受け入れ条件の<b>境界値</b>にあたる。「連続スキップ中は積まない」（AC-9）だけを
 * 実装して「変わり目では記録する」（AC-10）を落とすと、フラグを ON に戻した事実が
 * どこにも残らなくなるため、両方向の遷移を独立に固定する。</p>
 */
@DisplayName("BackgroundFeatureSkipRecorder 単体テスト（Gate基盤工事④-A AC-8〜AC-10）")
class BackgroundFeatureSkipRecorderTest {

    private static final String JOB = "shift-aggregate-daily";
    private static final String OTHER_JOB = "market-digest-daily";
    private static final String REASON = "FEATURE_SHIFT_ENABLED が無効のためスキップ";

    private BatchJobLogService batchJobLogService;
    private BackgroundFeatureSkipRecorder recorder;

    @BeforeEach
    void setUp() {
        batchJobLogService = mock(BatchJobLogService.class);
        recorder = new BackgroundFeatureSkipRecorder(batchJobLogService);
    }

    // ===============================================================
    // AC-8: 初回のスキップは記録される
    // ===============================================================

    @Test
    @DisplayName("(AC-8) フラグ無効でスキップした初回は batch_job_logs に記録される")
    void ac8_初回スキップは記録される() {
        boolean recorded = recorder.recordIfStateChanged(JOB, true, REASON);

        assertThat(recorded)
                .as("初回スキップは「直前が無い＝状態が変わった」として記録されねばならない")
                .isTrue();
        verify(batchJobLogService).recordFeaturePolicyOutcome(eq(JOB), eq(true), anyString());
    }

    @Test
    @DisplayName("(AC-8b) 記録内容から「フラグ無効でスキップした」と分かる（無効だったキーを含む）")
    void ac8b_記録内容がスキップと分かる形である() {
        recorder.recordIfStateChanged(JOB, true, REASON);

        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(batchJobLogService)
                .recordFeaturePolicyOutcome(eq(JOB), eq(true), reasonCaptor.capture());

        assertThat(reasonCaptor.getValue())
                .as("運用が batch_job_logs を見て「障害で落ちた」のか「意図して実行しなかった」のかを"
                        + "判別できねばならない。無効だったフラグキーが含まれていれば原因まで辿れる")
                .contains("FEATURE_SHIFT_ENABLED")
                .contains("スキップ");
    }

    // ===============================================================
    // AC-9: 連続してスキップし続ける間は記録を積まない
    // ===============================================================

    @Test
    @DisplayName("(AC-9) 連続してスキップし続ける間は 2 回目以降を記録しない")
    void ac9_連続スキップ中は記録を積まない() {
        assertThat(recorder.recordIfStateChanged(JOB, true, REASON)).isTrue();

        for (int i = 0; i < 10; i++) {
            assertThat(recorder.recordIfStateChanged(JOB, true, REASON))
                    .as("連続スキップの %d 回目は記録してはならない", i + 2)
                    .isFalse();
        }

        verify(batchJobLogService, times(1)).recordFeaturePolicyOutcome(eq(JOB), eq(true), anyString());
        verifyNoMoreInteractions(batchJobLogService);
    }

    @Test
    @DisplayName("(AC-9b) 連続して実行し続ける間もスキップ記録は一切書かれない")
    void ac9b_連続実行中はスキップ記録を書かない() {
        for (int i = 0; i < 5; i++) {
            assertThat(recorder.recordIfStateChanged(JOB, false, null)).isFalse();
        }

        verify(batchJobLogService, times(0)).recordFeaturePolicyOutcome(anyString(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("(AC-9c) 状態はジョブ名ごとに独立して保持される（別バッチの記録を抑止しない）")
    void ac9c_状態はジョブ名ごとに独立である() {
        assertThat(recorder.recordIfStateChanged(JOB, true, REASON)).isTrue();

        assertThat(recorder.recordIfStateChanged(OTHER_JOB, true, REASON))
                .as("状態を単一フィールドで持つと、あるバッチのスキップが"
                        + "別バッチの初回記録を握り潰す。ジョブ名ごとに保持すること")
                .isTrue();

        verify(batchJobLogService).recordFeaturePolicyOutcome(eq(JOB), eq(true), anyString());
        verify(batchJobLogService).recordFeaturePolicyOutcome(eq(OTHER_JOB), eq(true), anyString());
    }

    // ===============================================================
    // AC-10: 変わり目では記録される（両方向）
    // ===============================================================

    @Test
    @DisplayName("(AC-10a) スキップ → 実行 の変わり目で記録される")
    void ac10a_スキップから実行への変わり目で記録される() {
        recorder.recordIfStateChanged(JOB, true, REASON);

        assertThat(recorder.recordIfStateChanged(JOB, false, null))
                .as("フラグを ON に戻して再開した事実が残らないと、"
                        + "「いつから動き出したか」が batch_job_logs から読めなくなる")
                .isTrue();
    }

    @Test
    @DisplayName("(AC-10b) 実行 → スキップ の変わり目で記録される")
    void ac10b_実行からスキップへの変わり目で記録される() {
        assertThat(recorder.recordIfStateChanged(JOB, false, null)).isFalse();

        assertThat(recorder.recordIfStateChanged(JOB, true, REASON))
                .as("動いていたバッチが止まった変わり目は必ず記録されねばならない")
                .isTrue();

        verify(batchJobLogService).recordFeaturePolicyOutcome(eq(JOB), eq(true), anyString());
    }

    @Test
    @DisplayName("(AC-10c) スキップ→実行→スキップ と往復しても、変わり目の回数だけ記録される")
    void ac10c_往復しても変わり目の回数だけ記録される() {
        recorder.recordIfStateChanged(JOB, true, REASON);   // 記録(1) 初回スキップ
        recorder.recordIfStateChanged(JOB, true, REASON);   // 記録なし
        recorder.recordIfStateChanged(JOB, false, null);    // 変わり目
        recorder.recordIfStateChanged(JOB, false, null);    // 記録なし
        recorder.recordIfStateChanged(JOB, true, REASON);   // 記録(2) 変わり目
        recorder.recordIfStateChanged(JOB, true, REASON);   // 記録なし

        verify(batchJobLogService, times(2))
                .recordFeaturePolicyOutcome(eq(JOB), eq(true), anyString());
    }
}
