package com.mannschaft.app.common.backgroundgate;

import com.mannschaft.app.admin.service.BatchJobLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.dao.DataAccessResourceFailureException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
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
    // AC-8c〜AC-8e: 記録の失敗を隔離する（Codex 検分 P2）
    //
    // batch_job_logs が一時的に書けないことは起こりうる。そのとき例外がここから出ると
    // BackgroundFeaturePolicyAspect まで伝播し、AC-2「例外を投げずに正常終了」に反して
    // BatchExecutionAspect が FAILED を書き BatchFailedEvent を飛ばす。つまり
    // 「意図した停止」が「障害」として運用に通知される。記録は付随的な情報であり、
    // その失敗がバッチの停止判断そのものを障害に変えてはならない。
    //
    // 同時に、状態を保存より先に確定させると「失敗した遷移が二度と記録されない」
    // （次回は状態変化なしと判定される）。状態の確定は保存成功後でなければならない。
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
    @DisplayName("(AC-8e) 記録に失敗した回は状態が確定せず、次回に再度記録が試みられる")
    void ac8e_失敗した遷移は次回に再試行される() {
        doThrow(new DataAccessResourceFailureException("batch_job_logs へ書き込めない"))
                .doNothing()
                .when(batchJobLogService)
                .recordFeaturePolicyOutcome(anyString(), anyBoolean(), anyString());

        // 1回目: 保存が落ちる。状態を確定させてはならない。
        assertThat(recorder.recordIfStateChanged(JOB, true, REASON)).isFalse();

        // 2回目: 状態が未確定なのだから、同じ遷移がもう一度試みられねばならない。
        assertThat(recorder.recordIfStateChanged(JOB, true, REASON))
                .as("保存より先に lastSkipped を確定させると、次回は「状態変化なし」と判定され、"
                        + "失敗した遷移記録が永久に再試行されない")
                .isTrue();

        verify(batchJobLogService, times(2))
                .recordFeaturePolicyOutcome(eq(JOB), eq(true), anyString());
    }

    @Test
    @DisplayName("(AC-8f) 記録に成功した後は状態が確定し、連続スキップを積まない（再試行が暴走しない）")
    void ac8f_成功後は状態が確定する() {
        doThrow(new DataAccessResourceFailureException("batch_job_logs へ書き込めない"))
                .doNothing()
                .when(batchJobLogService)
                .recordFeaturePolicyOutcome(anyString(), anyBoolean(), anyString());

        recorder.recordIfStateChanged(JOB, true, REASON);   // 失敗（状態は未確定のまま）
        recorder.recordIfStateChanged(JOB, true, REASON);   // 成功（ここで状態が確定）

        assertThat(recorder.recordIfStateChanged(JOB, true, REASON))
                .as("成功後まで状態を確定させないと、毎回リトライして AC-9（連続スキップ中は積まない）が壊れる")
                .isFalse();

        verify(batchJobLogService, times(2))
                .recordFeaturePolicyOutcome(eq(JOB), eq(true), anyString());
    }

    // ===============================================================
    // AC-8g / AC-8h: 直列化と pending 再試行（Codex 検分2巡目 P2-1 / P2-2）
    // ===============================================================

    @Test
    @DisplayName("(AC-8g) 同一ジョブへの並行呼び出しでも遷移は1回しか保存されない（二重記録しない）")
    void ac8g_同一ジョブの並行呼び出しで二重に保存されない() throws Exception {
        // 保存に実測可能な所要時間を持たせ、「読んでから書くまで」の窓を実際に開ける。
        // 窓が無いと逐次実行と区別が付かず、直列化していない実装でも偶然通ってしまう。
        doAnswer(invocation -> {
            Thread.sleep(100);
            return null;
        }).when(batchJobLogService).recordFeaturePolicyOutcome(anyString(), anyBoolean(), anyString());

        int threads = 16;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger recordedTrue = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        if (recorder.recordIfStateChanged(JOB, true, REASON)) {
                            recordedTrue.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS))
                    .as("並行呼び出しがデッドロックせず全て終了すること")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        verify(batchJobLogService, times(1))
                .recordFeaturePolicyOutcome(eq(JOB), eq(true), anyString());
        assertThat(recordedTrue.get())
                .as("ConcurrentHashMap は個々の操作しか原子化しない。read→保存→write を"
                        + "ジョブ単位で直列化しないと、全スレッドが同じ previous を読んで"
                        + "同一の遷移を何度も保存する")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("(AC-8h) 保存失敗後に状態が反転して呼ばれても、失われた遷移が記録される")
    void ac8h_状態が反転しても失敗した遷移は失われない() {
        doThrow(new DataAccessResourceFailureException("batch_job_logs へ書き込めない"))
                .doNothing()
                .when(batchJobLogService)
                .recordFeaturePolicyOutcome(anyString(), anyBoolean(), anyString());

        // 1回目: 停止（skipped=true）の記録が落ちる。
        assertThat(recorder.recordIfStateChanged(JOB, true, REASON)).isFalse();

        // 2回目: 再有効化され状態が反転した（skipped=false）。
        assertThat(recorder.recordIfStateChanged(JOB, false, null))
                .as("失敗した遷移を「前回状態」だけで表現すると、状態が反転した瞬間に"
                        + "「初期状態と同じ」と判定され、停止と再開の両方が永久に欠落する")
                .isTrue();

        // 停止（true）は再試行され、再開（false）も記録されること。
        verify(batchJobLogService, times(2))
                .recordFeaturePolicyOutcome(eq(JOB), eq(true), anyString());
        verify(batchJobLogService, times(1))
                .recordFeaturePolicyOutcome(eq(JOB), eq(false), anyString());
    }

    @Test
    @DisplayName("(AC-8i) 反転後の再試行が済んだら、以降は状態が確定して積み増さない")
    void ac8i_反転後の再試行が済めば状態は確定する() {
        doThrow(new DataAccessResourceFailureException("batch_job_logs へ書き込めない"))
                .doNothing()
                .when(batchJobLogService)
                .recordFeaturePolicyOutcome(anyString(), anyBoolean(), anyString());

        recorder.recordIfStateChanged(JOB, true, REASON);   // 失敗（pending=true）
        recorder.recordIfStateChanged(JOB, false, null);    // pending 再試行 + 反転の記録

        assertThat(recorder.recordIfStateChanged(JOB, false, null))
                .as("再試行が済んだ後も pending が残っていると、毎回記録され AC-9 が壊れる")
                .isFalse();

        verify(batchJobLogService, times(3))
                .recordFeaturePolicyOutcome(anyString(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("(AC-8j) pending はジョブ単位で独立している（別ジョブの再試行に混線しない）")
    void ac8j_pendingはジョブ単位で独立である() {
        doThrow(new DataAccessResourceFailureException("batch_job_logs へ書き込めない"))
                .doNothing()
                .when(batchJobLogService)
                .recordFeaturePolicyOutcome(eq(JOB), anyBoolean(), anyString());

        assertThat(recorder.recordIfStateChanged(JOB, true, REASON)).isFalse();

        // 別ジョブは JOB の pending に影響されず、自分の初回遷移だけを記録する。
        assertThat(recorder.recordIfStateChanged(OTHER_JOB, true, REASON)).isTrue();
        verify(batchJobLogService, times(1))
                .recordFeaturePolicyOutcome(eq(OTHER_JOB), eq(true), anyString());
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
