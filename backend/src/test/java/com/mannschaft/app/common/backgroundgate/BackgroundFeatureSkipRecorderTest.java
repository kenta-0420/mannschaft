package com.mannschaft.app.common.backgroundgate;

import com.mannschaft.app.admin.BatchJobStatus;
import com.mannschaft.app.admin.entity.BatchJobLogEntity;
import com.mannschaft.app.admin.service.BatchJobLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BackgroundFeatureSkipRecorder} の単体テスト
 * （Gate 基盤工事④-A / 受け入れ条件 AC-8〜AC-10）。
 *
 * <h2>マスター裁可: 「状態が変わった時だけ記録する」</h2>
 * <p>毎分走るバッチをβ期間中ずっと無効にしておくと、素朴に毎回記録した場合
 * {@code batch_job_logs} がスキップ行で埋まり、本当に見たい実行履歴が読めなくなる。
 * よって<b>初回・および直前の結果と変わった時にだけ</b> 1 行記録する。</p>
 *
 * <h2>前回状態は {@code batch_job_logs} が唯一の正（マスター裁可・設計変更）</h2>
 * <p>インメモリの前回状態は全廃された。よって本テストも
 * <b>「直近 1 行を返す DB」を模した stub を組み、その DB を通して</b>受け入れ条件を検証する。
 * 記録が成功したら DB の直近行が入れ替わり、失敗したら入れ替わらない、という
 * 本番と同じ因果を stub 側に持たせている（成功したことにして状態だけ進む、という
 * 実装の都合に合わせた偽の緑を作らないため）。</p>
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

    /** 「ジョブごとの直近 1 行」を持つ DB の代役。 */
    private final Map<String, BatchJobLogEntity> latestRowByJob = new HashMap<>();

    private BatchJobLogService batchJobLogService;
    private BackgroundFeatureSkipRecorder recorder;

    @BeforeEach
    void setUp() {
        batchJobLogService = mock(BatchJobLogService.class);
        latestRowByJob.clear();

        // 読み取り: 当該ジョブの直近 1 行を返す（無ければ空）。
        when(batchJobLogService.findLatestByJobName(anyString()))
                .thenAnswer(invocation ->
                        Optional.ofNullable(latestRowByJob.get(invocation.<String>getArgument(0))));

        // 書き込み: 成功したら直近 1 行が入れ替わる（本番と同じ因果）。
        stubSuccessfulWrite();

        recorder = new BackgroundFeatureSkipRecorder(batchJobLogService);
    }

    private void stubSuccessfulWrite() {
        doAnswer(invocation -> {
            String jobName = invocation.getArgument(0);
            boolean skipped = invocation.getArgument(1);
            latestRowByJob.put(jobName, row(jobName, skipped));
            return null;
        }).when(batchJobLogService)
                .recordFeaturePolicyOutcome(anyString(), anyBoolean(), anyString());
    }

    private static BatchJobLogEntity row(String jobName, boolean skipped) {
        return BatchJobLogEntity.builder()
                .jobName(jobName)
                .status(skipped ? BatchJobStatus.SKIPPED : BatchJobStatus.RESUMED)
                .startedAt(LocalDateTime.now())
                .build();
    }

    /** 通常実行（{@code BatchExecutionAspect} が書く行）を直近 1 行として置く。 */
    private void givenLatestRowIsNormalExecution(String jobName, BatchJobStatus status) {
        latestRowByJob.put(jobName, BatchJobLogEntity.builder()
                .jobName(jobName)
                .status(status)
                .startedAt(LocalDateTime.now())
                .build());
    }

    // ===============================================================
    // AC-8: 初回のスキップは記録される
    // ===============================================================

    @Test
    @DisplayName("(AC-8) フラグ無効でスキップした初回は batch_job_logs に記録される")
    void ac8_初回スキップは記録される() {
        boolean recorded = recorder.recordIfStateChanged(JOB, true, REASON);

        assertThat(recorded)
                .as("履歴が無い＝「動いていた」とみなすため、初回スキップは状態変化として記録される")
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

        verify(batchJobLogService, times(1))
                .recordFeaturePolicyOutcome(anyString(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("(AC-9b) 連続して実行し続ける間もスキップ記録は一切書かれない")
    void ac9b_連続実行中はスキップ記録を書かない() {
        for (int i = 0; i < 5; i++) {
            assertThat(recorder.recordIfStateChanged(JOB, false, null)).isFalse();
        }

        verify(batchJobLogService, never())
                .recordFeaturePolicyOutcome(anyString(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("(AC-9c) 状態はジョブ名ごとに独立して判定される（別バッチの記録を抑止しない）")
    void ac9c_状態はジョブ名ごとに独立である() {
        assertThat(recorder.recordIfStateChanged(JOB, true, REASON)).isTrue();

        assertThat(recorder.recordIfStateChanged(OTHER_JOB, true, REASON))
                .as("直近 1 行はジョブ名で引く。別ジョブのスキップが"
                        + "このジョブの初回記録を握り潰してはならない")
                .isTrue();

        verify(batchJobLogService).recordFeaturePolicyOutcome(eq(JOB), eq(true), anyString());
        verify(batchJobLogService).recordFeaturePolicyOutcome(eq(OTHER_JOB), eq(true), anyString());
    }

    @Test
    @DisplayName("(AC-9d) 直近1行が通常実行（SUCCESS/RUNNING/FAILED）なら「動いていた」と読む")
    void ac9d_通常実行の行は動いていたと読む() {
        for (BatchJobStatus status : new BatchJobStatus[]{
                BatchJobStatus.SUCCESS, BatchJobStatus.RUNNING, BatchJobStatus.FAILED}) {
            latestRowByJob.clear();
            givenLatestRowIsNormalExecution(JOB, status);

            assertThat(recorder.recordIfStateChanged(JOB, false, null))
                    .as("直近が %s なら動いていたのだから、実行は状態変化ではない", status)
                    .isFalse();
            assertThat(recorder.recordIfStateChanged(JOB, true, REASON))
                    .as("直近が %s なら、スキップへの遷移は記録されねばならない", status)
                    .isTrue();
        }
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

        verify(batchJobLogService).recordFeaturePolicyOutcome(eq(JOB), eq(false), anyString());
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
        verify(batchJobLogService, times(1))
                .recordFeaturePolicyOutcome(eq(JOB), eq(false), anyString());
    }

    @Test
    @DisplayName("(AC-10d) 再開の記録は SKIPPED ではない行として残る（次回に「まだスキップ中」と誤読されない）")
    void ac10d_再開の行はスキップとして読み戻されない() {
        recorder.recordIfStateChanged(JOB, true, REASON);
        recorder.recordIfStateChanged(JOB, false, null);

        // 再開の直後にもう一度「実行」で呼ばれても、状態変化ではないので何も書かない。
        assertThat(recorder.recordIfStateChanged(JOB, false, null))
                .as("再開の行が SKIPPED のままだと「まだスキップ中」と読み戻され、"
                        + "実行のたびに再開行を積み続ける")
                .isFalse();
    }

    // ===============================================================
    // AC-8c / AC-8d / AC-8e: 読み書きの失敗を隔離する
    //
    // batch_job_logs が一時的に読めない・書けないことは起こりうる。そのとき例外がここから出ると
    // BackgroundFeaturePolicyAspect まで伝播し、AC-2「例外を投げずに正常終了」に反して
    // BatchExecutionAspect が FAILED を書き BatchFailedEvent を飛ばす。つまり
    // 「意図した停止」が「障害」として運用に通知される。
    // ===============================================================

    @Test
    @DisplayName("(AC-8c) 記録が例外を投げても呼び出し元へ伝播しない（記録失敗を障害にしない）")
    void ac8c_記録の失敗は呼び出し元へ伝播しない() {
        doThrow(new DataAccessResourceFailureException("batch_job_logs へ書き込めない"))
                .when(batchJobLogService)
                .recordFeaturePolicyOutcome(anyString(), anyBoolean(), anyString());

        assertThatCode(() -> recorder.recordIfStateChanged(JOB, true, REASON))
                .as("ここで例外を投げると Aspect まで伝播し、BatchExecutionAspect が FAILED を書いて "
                        + "BatchFailedEvent を飛ばす。記録できなかっただけの回が「障害」として"
                        + "運用に通知されてしまう")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("(AC-8d) 記録に失敗した回は false を返す（書けていないのに書いたと申告しない）")
    void ac8d_記録に失敗した回はfalseを返す() {
        doThrow(new DataAccessResourceFailureException("batch_job_logs へ書き込めない"))
                .when(batchJobLogService)
                .recordFeaturePolicyOutcome(anyString(), anyBoolean(), anyString());

        assertThat(recorder.recordIfStateChanged(JOB, true, REASON))
                .as("戻り値の契約は「実際に記録を書いたなら true」である。"
                        + "書けなかった回に true を返すと、呼び出し側から失敗が見えなくなる")
                .isFalse();
    }

    @Test
    @DisplayName("(AC-8e) 記録に失敗しても台帳は変わらないため、同じ状態が続けば次回に再試行される")
    void ac8e_失敗した遷移は状態が続く限り次回に再試行される() {
        doThrow(new DataAccessResourceFailureException("batch_job_logs へ書き込めない"))
                .when(batchJobLogService)
                .recordFeaturePolicyOutcome(anyString(), anyBoolean(), anyString());

        assertThat(recorder.recordIfStateChanged(JOB, true, REASON)).isFalse();

        // 書けていない＝台帳の直近 1 行は変わっていない。よって次回も「変わり目」と判定される。
        stubSuccessfulWrite();
        assertThat(recorder.recordIfStateChanged(JOB, true, REASON))
                .as("台帳が唯一の状態なので、書けなかった遷移は状態が続く限り自然に再試行される"
                        + "（保留機構を持たずにこの性質が成り立つ）")
                .isTrue();

        verify(batchJobLogService, times(2))
                .recordFeaturePolicyOutcome(eq(JOB), eq(true), anyString());
    }

    @Test
    @DisplayName("(AC-8f) 記録に成功した後は台帳が進み、連続スキップを積まない")
    void ac8f_成功後は台帳が進む() {
        recorder.recordIfStateChanged(JOB, true, REASON);

        assertThat(recorder.recordIfStateChanged(JOB, true, REASON))
                .as("成功して台帳が進んだ後は、同じ状態で呼ばれても記録してはならない")
                .isFalse();

        verify(batchJobLogService, times(1))
                .recordFeaturePolicyOutcome(anyString(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("(AC-8g) 直近履歴の読み取りが例外を投げても伝播せず、記録も書かない")
    void ac8g_読み取りの失敗は伝播せず記録も書かない() {
        when(batchJobLogService.findLatestByJobName(anyString()))
                .thenThrow(new DataAccessResourceFailureException("batch_job_logs を読めない"));

        assertThatCode(() -> recorder.recordIfStateChanged(JOB, true, REASON))
                .as("読み取りの失敗もバッチの障害にしてはならない（AC-2）")
                .doesNotThrowAnyException();

        assertThat(recorder.recordIfStateChanged(JOB, true, REASON)).isFalse();

        verify(batchJobLogService, never())
                .recordFeaturePolicyOutcome(anyString(), anyBoolean(), anyString());
    }
}
