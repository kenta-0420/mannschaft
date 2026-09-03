package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;

/**
 * Issue #2990 L4 検分是正 — AI 分析失敗時の FAILED 記録が巻き戻らないことの実 DB 検証。
 *
 * <h2>本 PR が作り出した退行</h2>
 * <p>{@link ErrorReportAiAnalysisService#analyzeSync} の catch 節は
 * 「再試行ループを防ぐため {@code last_ai_analysis_at} は更新する」と明言したうえで
 * FAILED 行を保存し、そのあと例外を投げ直す。ところが本 PR で {@code @Transactional} が
 * <b>初めて実効化した</b>ため（是正前は自己呼び出しでプロキシを経ず失効していた）、
 * 既定のロールバック規則により FAILED 行も {@code last_ai_analysis_at} の更新も
 * 投げ直した例外と一緒に巻き戻るようになった。宣言された意図と実際の挙動が矛盾する。</p>
 *
 * <p>実害は無限の再試行ループである。{@link ErrorReportAiAnalysisBatch} は
 * {@code last_ai_analysis_at IS NULL} を検索条件にしているため、恒常的に失敗するレポート
 * （例: サニタイズ後のコンテキストが Claude API に弾かれ続ける等）を 5 分ごとに
 * 永久に AI へ投げ続け、月次予算を焼き切る。</p>
 *
 * <h2>なぜ実 DB の IT なのか</h2>
 * <p>この欠陥はロールバックという<b>トランザクションのふるまい</b>そのものである。
 * リポジトリをモックにした単体テストでは {@code save} が呼ばれたことしか見えず、
 * 是正前でも緑になる（実際 {@code ErrorReportAiAnalysisServiceTest} は是正前後どちらでも緑）。
 * 実 DB とコミットを伴う経路でしか捕まらない。</p>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>テストメソッドをトランザクションで包むと、内側の {@code REQUIRES_NEW} が
 * 独立して確定したのかテストTXの第一次キャッシュを見ているだけなのかを区別できず<b>偽の緑</b>になる。
 * よってトランザクションを張らず、フィクスチャ投入・検証読み取りは
 * {@link TransactionTemplate} と {@link JdbcTemplate} で明示的に行う。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2990 L4 AI 分析失敗の FAILED 記録が巻き戻らない（実DB）")
class ErrorReportAiAnalysisFailureRecordIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private ErrorReportAiAnalysisService aiAnalysisService;

    @Autowired
    private ErrorReportRepository errorReportRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** AI 呼び出しを必ず失敗させる（catch 節へ入れるための唯一の仕掛け）。 */
    @MockitoBean
    private ErrorReportClaudeAiProvider provider;

    /**
     * 予算ガードは Valkey（Redis）を参照するため、Testcontainers の MySQL しか無い IT 環境では
     * {@code opsForValue()} が null となり <b>予算チェックの時点で NPE</b> になる。
     * 本 IT が検証したいのは「AI 呼び出しが失敗したときの記録が巻き戻らないか」であって
     * 予算ガードではないため、ここは通す側に固定する（実測で一度この理由の赤を踏んだ）。
     */
    @MockitoBean
    private ErrorReportAiBudgetService budgetService;

    @Test
    @DisplayName("AI 呼び出しが失敗しても FAILED 履歴と last_ai_analysis_at が残り、後追いバッチが再試行しない")
    void 失敗記録は巻き戻らない() {
        given(budgetService.canExpend(anyInt())).willReturn(true);
        given(provider.analyze(any())).willThrow(new IllegalStateException("Claude API 呼び出しに失敗"));

        String hash = "l4fail" + System.nanoTime();
        LocalDateTime occurredAt = LocalDateTime.now().minusHours(2);
        Long reportId = transactionTemplate.execute(tx -> errorReportRepository.save(
                ErrorReportEntity.builder()
                        .errorMessage("Issue #2990 L4 検分是正の検証用エラー")
                        .pageUrl("/test/issue2990-l4")
                        .occurredAt(occurredAt)
                        .status(ErrorReportStatus.NEW)
                        .severity(ErrorReportSeverity.LOW)
                        .errorHash(hash)
                        .occurrenceCount(1)
                        .affectedUserCount(1)
                        .firstOccurredAt(occurredAt)
                        .lastOccurredAt(occurredAt)
                        .build()).getId());

        // ① 失敗は失敗として上位へ伝わる（握りつぶしていない）。
        assertThatThrownBy(() -> aiAnalysisService.analyzeSync(reportId, null))
                .as("AI 分析の失敗は呼び出し元へ伝播すること（例外を投げるのをやめる誤魔化しをしていない）")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("AI 分析に失敗しました");

        // ② FAILED 履歴が実 DB に残っている（是正前は例外と一緒に巻き戻り 0 件になる）。
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT status, error_message FROM error_report_ai_analyses WHERE error_report_id = ?",
                reportId);
        assertThat(rows)
                .as("FAILED 履歴が独立トランザクションで確定していること")
                .hasSize(1);
        assertThat(rows.get(0).get("status")).isEqualTo("FAILED");

        // ③ last_ai_analysis_at が更新されている（是正前は NULL のまま）。
        LocalDateTime lastAnalyzedAt = transactionTemplate.execute(
                tx -> errorReportRepository.findById(reportId).orElseThrow().getLastAiAnalysisAt());
        assertThat(lastAnalyzedAt)
                .as("再試行ループを防ぐための last_ai_analysis_at 更新が確定していること")
                .isNotNull();

        // ④ 後追いバッチの検索条件から外れている＝再試行ループに陥らない。
        List<Long> retryTargets = transactionTemplate.execute(tx -> errorReportRepository
                .findByLastAiAnalysisAtIsNullAndCreatedAtBefore(
                        LocalDateTime.now().plusMinutes(1), PageRequest.of(0, 100))
                .stream().map(ErrorReportEntity::getId).toList());
        assertThat(retryTargets)
                .as("AI 分析バッチ（last_ai_analysis_at IS NULL が検索条件）が同じレポートを拾い直さないこと")
                .doesNotContain(reportId);
    }
}
