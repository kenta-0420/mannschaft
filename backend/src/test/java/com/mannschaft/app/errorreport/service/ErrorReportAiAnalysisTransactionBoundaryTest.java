package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.errorreport.controller.SystemAdminErrorReportController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #2990 L4 再検分是正 — AI 分析のトランザクション境界を宣言レベルで固定する番人。
 *
 * <h2>何を守っているのか</h2>
 * <p>{@link ErrorReportAiAnalysisService#analyzeSync} は「①読み取り → ②AI 呼び出し（<b>TX外</b>）
 * → ③書き込み」の3段に分けてある。②を TX の中に戻すと、次の3つが同時に復活する。</p>
 * <ol>
 *   <li><b>接続保持</b> — AI 応答を待つ秒〜分のあいだ Hikari 接続を握り続ける。管理者の再分析 API は
 *       HTTP スレッドから直接この経路を叩くため {@code ai-analysis-pool} の max2 では縛れない</li>
 *   <li><b>接続枯渇</b> — 失敗時に {@code REQUIRES_NEW} の失敗記録が追加接続を要求し、
 *       接続取得タイムアウトで FAILED 記録自体が失敗する（＝再試行ループ防止が破れる）</li>
 *   <li><b>自己デッドロック</b> — 外側TXが {@code error_reports} の行ロックを保持したまま、
 *       同じ行を更新する {@code REQUIRES_NEW} を待つ</li>
 * </ol>
 *
 * <p>これらは単体テストのモックでは現れない（モックが TX の実体を消す）ため、宣言そのものを検体にする。</p>
 */
@DisplayName("Issue #2990 L4: AI 分析のトランザクション境界")
class ErrorReportAiAnalysisTransactionBoundaryTest {

    private static Method method(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(type.getSimpleName() + "#" + name + " が見当たらない"));
    }

    @Test
    @DisplayName("analyzeSync は @Transactional を持たない（AI 呼び出しを TX の中に入れない）")
    void analyzeSyncは非トランザクション() {
        assertThat(method(ErrorReportAiAnalysisService.class, "analyzeSync")
                .getAnnotation(Transactional.class))
                .as("analyzeSync に @Transactional を付けると Claude API 呼び出しが TX の中に入り、"
                        + "接続保持・接続枯渇・自己デッドロックが同時に復活する")
                .isNull();
        assertThat(ErrorReportAiAnalysisService.class.getAnnotation(Transactional.class))
                .as("クラスレベルの @Transactional も付けないこと")
                .isNull();
    }

    @Test
    @DisplayName("同期呼び出し元（再分析 API）も TX を張らない")
    void 呼び出し元も非トランザクション() {
        assertThat(SystemAdminErrorReportController.class.getAnnotation(Transactional.class))
                .as("コントローラに @Transactional を付けると analyzeSync が外側TXの中で走る")
                .isNull();
        assertThat(method(SystemAdminErrorReportController.class, "reanalyze")
                .getAnnotation(Transactional.class))
                .as("再分析 API に @Transactional を付けると analyzeSync が外側TXの中で走る")
                .isNull();
    }

    @Test
    @DisplayName("書き込みは短命TXの別 Bean（成功=@Transactional / 失敗=REQUIRES_NEW）")
    void 書き込みは別Beanの短命TX() {
        Transactional success = method(ErrorReportAiAnalysisResultRecorder.class, "recordSuccess")
                .getAnnotation(Transactional.class);
        assertThat(success).as("recordSuccess に @Transactional があること").isNotNull();

        Transactional failure = method(ErrorReportAiAnalysisFailureRecorder.class, "recordFailure")
                .getAnnotation(Transactional.class);
        assertThat(failure).as("recordFailure に @Transactional があること").isNotNull();
        assertThat(failure.propagation())
                .as("recordFailure は REQUIRES_NEW（万一外側TXから呼ばれても巻き添えで消えないため）")
                .isEqualTo(Propagation.REQUIRES_NEW);
    }
}
