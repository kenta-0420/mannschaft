package com.mannschaft.app.errorreport.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.errorreport.ErrorReportActivityType;
import com.mannschaft.app.errorreport.ErrorReportErrorCode;
import com.mannschaft.app.errorreport.ErrorReportProperties;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.entity.ErrorReportAiAnalysisEntity;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportAiAnalysisRepository;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F12.5 Phase 2-D — {@link GitHubIssueService} 単体テスト。
 *
 * <p>{@code restClient.post()...} の実 HTTP 呼び出しを伴う成功系は
 * {@link GitHubIssueServiceMockServerTest} 側でカバーする。本テストは
 * 設定不備・重複作成・ロック取得失敗・本文構築（Sanitizer 経由）を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GitHubIssueService 単体テスト")
class GitHubIssueServiceTest {

    @Mock
    private ErrorReportRepository errorReportRepository;
    @Mock
    private ErrorReportAiAnalysisRepository aiAnalysisRepository;
    @Mock
    private ErrorReportActivityService activityService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    private ObjectMapper objectMapper;
    private ErrorReportSanitizer sanitizer;
    private ErrorReportProperties props;
    private GitHubIssueService service;

    private static final Long REPORT_ID = 100L;
    private static final Long ACTOR_ID = 7L;
    private static final String APP_BASE_URL = "https://app.mannschaft.local";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        sanitizer = new ErrorReportSanitizer();
        props = new ErrorReportProperties();
        props.getGithub().setEnabled(true);

        service = new GitHubIssueService(
                objectMapper, sanitizer, props,
                errorReportRepository, aiAnalysisRepository,
                activityService, redisTemplate);

        // 環境変数相当の値を ReflectionTestUtils で注入
        ReflectionTestUtils.setField(service, "token", "ghp_dummy_token_for_test");
        ReflectionTestUtils.setField(service, "owner", "octocat");
        ReflectionTestUtils.setField(service, "repo", "hello-world");
        ReflectionTestUtils.setField(service, "appBaseUrl", APP_BASE_URL);

        // Redis モックは多くのテストで使うため lenient で設定
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
    }

    private ErrorReportEntity sampleReport(ErrorReportSeverity severity, ErrorReportStatus status) {
        return ErrorReportEntity.builder()
                .errorMessage("TypeError: Cannot read property of null")
                .stackTrace("at /app/page.vue:42:10")
                .pageUrl("https://app.mannschaft.local/foo/123?token=secret")
                .occurredAt(LocalDateTime.now())
                .status(status)
                .severity(severity)
                .errorHash("h")
                .occurrenceCount(5)
                .affectedUserCount(3)
                .firstOccurredAt(LocalDateTime.of(2026, 5, 1, 10, 0))
                .lastOccurredAt(LocalDateTime.of(2026, 5, 6, 12, 0))
                .build();
        }

    // ===== isAvailable =====

    @Test
    @DisplayName("isAvailable: enabled=false なら false")
    void isAvailable_falseWhenDisabled() {
        props.getGithub().setEnabled(false);
        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("isAvailable: token 空なら false")
    void isAvailable_falseWhenTokenBlank() {
        ReflectionTestUtils.setField(service, "token", "");
        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("isAvailable: owner 空なら false")
    void isAvailable_falseWhenOwnerBlank() {
        ReflectionTestUtils.setField(service, "owner", "");
        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("isAvailable: repo 空なら false")
    void isAvailable_falseWhenRepoBlank() {
        ReflectionTestUtils.setField(service, "repo", "");
        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("isAvailable: 全て揃っていれば true")
    void isAvailable_trueWhenAllSet() {
        assertThat(service.isAvailable()).isTrue();
    }

    // ===== createIssue: 設定不備 =====

    @Test
    @DisplayName("createIssue: enabled=false なら ERROR_REPORT_010")
    void createIssue_throwsWhenDisabled() {
        props.getGithub().setEnabled(false);
        assertThatThrownBy(() -> service.createIssue(REPORT_ID, ACTOR_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorReportErrorCode.ERROR_REPORT_010.getMessage());
        verify(errorReportRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("createIssue: token 空なら ERROR_REPORT_010")
    void createIssue_throwsWhenTokenBlank() {
        ReflectionTestUtils.setField(service, "token", "");
        assertThatThrownBy(() -> service.createIssue(REPORT_ID, ACTOR_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorReportErrorCode.ERROR_REPORT_010.getMessage());
    }

    // ===== createIssue: 既存 URL =====

    @Test
    @DisplayName("createIssue: 既存 githubIssueUrl がある場合は ERROR_REPORT_012")
    void createIssue_throwsWhenAlreadyCreated() {
        ErrorReportEntity report = sampleReport(ErrorReportSeverity.HIGH, ErrorReportStatus.NEW);
        report.setGithubIssueUrl("https://github.com/octocat/hello-world/issues/1");
        given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));

        assertThatThrownBy(() -> service.createIssue(REPORT_ID, ACTOR_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorReportErrorCode.ERROR_REPORT_012.getMessage());
    }

    @Test
    @DisplayName("createIssue: レポート未存在なら ERROR_REPORT_NOT_FOUND")
    void createIssue_throwsWhenReportMissing() {
        given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.createIssue(REPORT_ID, ACTOR_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorReportErrorCode.ERROR_REPORT_NOT_FOUND.getMessage());
    }

    // ===== createIssue: ロック取得失敗 =====

    @Test
    @DisplayName("createIssue: Valkey ロック取得失敗時は ERROR_REPORT_009")
    void createIssue_throwsWhenLockBusy() {
        ErrorReportEntity report = sampleReport(ErrorReportSeverity.HIGH, ErrorReportStatus.NEW);
        given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
        given(valueOps.setIfAbsent(any(), any(), any(Duration.class))).willReturn(false);

        assertThatThrownBy(() -> service.createIssue(REPORT_ID, ACTOR_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorReportErrorCode.ERROR_REPORT_009.getMessage());

        verify(activityService, never()).record(anyLong(), any(), any(), any(), anyMap());
    }

    // ===== buildTitle / buildBody =====

    @Test
    @DisplayName("buildTitle: severity プレフィックス + 100 文字超は ... で切り詰め")
    void buildTitle_truncates() {
        ErrorReportEntity r = sampleReport(ErrorReportSeverity.CRITICAL, ErrorReportStatus.NEW);
        // 150 文字のメッセージ
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 150; i++) sb.append('A');
        ReflectionTestUtils.setField(r, "errorMessage", sb.toString());

        String title = service.buildTitle(r);

        assertThat(title).startsWith("[CRITICAL] ");
        assertThat(title).endsWith("...");
        // [CRITICAL] + 100文字 + ...
        assertThat(title.length()).isLessThanOrEqualTo("[CRITICAL] ".length() + 100 + 3);
    }

    @Test
    @DisplayName("buildBody: PII（メールアドレス）が必ず除去される")
    void buildBody_redactsPii() {
        ErrorReportEntity r = sampleReport(ErrorReportSeverity.HIGH, ErrorReportStatus.NEW);
        ReflectionTestUtils.setField(r, "errorMessage",
                "User foo@example.com cannot login");
        ReflectionTestUtils.setField(r, "stackTrace",
                "Authorization: Bearer abcdef123\nat /app/login.ts:10");

        String body = service.buildBody(r, null);

        assertThat(body).doesNotContain("foo@example.com");
        assertThat(body).contains("[REDACTED-EMAIL]");
        assertThat(body).doesNotContain("Bearer abcdef123");
        assertThat(body).contains("[REDACTED-AUTH]");
    }

    @Test
    @DisplayName("buildBody: AI 分析あり → 推定原因/修正案/影響評価/関連ファイルが含まれる")
    void buildBody_includesAiAnalysis() {
        ErrorReportEntity r = sampleReport(ErrorReportSeverity.HIGH, ErrorReportStatus.NEW);
        ErrorReportAiAnalysisEntity ai = ErrorReportAiAnalysisEntity.builder()
                .errorReportId(REPORT_ID)
                .modelName("claude-haiku-4-5")
                .estimatedCause("null チェック漏れ")
                .fixProposal("early return を追加")
                .impactAssessment("低: ログイン画面のみ")
                .suggestedFiles("login.vue,auth.ts")
                .status("SUCCESS")
                .build();

        String body = service.buildBody(r, ai);

        assertThat(body).contains("## AI 分析（参考）");
        assertThat(body).contains("null チェック漏れ");
        assertThat(body).contains("early return を追加");
        assertThat(body).contains("低: ログイン画面のみ");
        assertThat(body).contains("login.vue,auth.ts");
    }

    @Test
    @DisplayName("buildBody: AI 分析なし → AI セクション無し")
    void buildBody_noAiSection() {
        ErrorReportEntity r = sampleReport(ErrorReportSeverity.HIGH, ErrorReportStatus.NEW);
        String body = service.buildBody(r, null);
        assertThat(body).doesNotContain("## AI 分析");
    }

    @Test
    @DisplayName("buildBody: AI が FAILED の場合は AI セクション無し")
    void buildBody_skipsFailedAi() {
        ErrorReportEntity r = sampleReport(ErrorReportSeverity.HIGH, ErrorReportStatus.NEW);
        ErrorReportAiAnalysisEntity ai = ErrorReportAiAnalysisEntity.builder()
                .errorReportId(REPORT_ID)
                .modelName("claude-haiku-4-5")
                .status("FAILED")
                .errorMessage("API timeout")
                .build();
        String body = service.buildBody(r, ai);
        assertThat(body).doesNotContain("## AI 分析");
        assertThat(body).doesNotContain("API timeout");
    }

    @Test
    @DisplayName("buildBody: 内部管理画面 URL が含まれる")
    void buildBody_includesInternalUrl() {
        ErrorReportEntity r = sampleReport(ErrorReportSeverity.HIGH, ErrorReportStatus.NEW);
        ReflectionTestUtils.setField(r, "id", REPORT_ID);
        String body = service.buildBody(r, null);
        assertThat(body).contains(APP_BASE_URL + "/system-admin/error-reports/" + REPORT_ID);
    }

    @Test
    @DisplayName("buildBody: ?token=xxx クエリは sanitizePagePath で除去")
    void buildBody_sanitizesPageUrl() {
        ErrorReportEntity r = sampleReport(ErrorReportSeverity.HIGH, ErrorReportStatus.NEW);
        // sample の pageUrl は ?token=secret 付き
        String body = service.buildBody(r, null);
        assertThat(body).doesNotContain("token=secret");
    }

    // ===== stripExternalUrls =====

    @Test
    @DisplayName("stripExternalUrls: 自ドメインリンクは保持される")
    void stripExternalUrls_keepsSelfDomain() {
        String input = "[管理画面](https://app.mannschaft.local/foo) を確認";
        String result = service.stripExternalUrls(input);
        assertThat(result).isEqualTo(input);
    }

    @Test
    @DisplayName("stripExternalUrls: 外部 URL は [text] に縮約される")
    void stripExternalUrls_stripsExternal() {
        String input = "[evil](https://evil.example.com/x) と [docs](https://docs.example.com)";
        String result = service.stripExternalUrls(input);
        assertThat(result).isEqualTo("[evil] と [docs]");
    }

    @Test
    @DisplayName("stripExternalUrls: NULL は NULL を返す")
    void stripExternalUrls_null() {
        assertThat(service.stripExternalUrls(null)).isNull();
    }

    @Test
    @DisplayName("stripExternalUrls: リンクが無いテキストはそのまま返る")
    void stripExternalUrls_plain() {
        String input = "ただの本文です";
        assertThat(service.stripExternalUrls(input)).isEqualTo(input);
    }

    // ===== 設定不備フローでは activityService.record が呼ばれない =====

    @Test
    @DisplayName("createIssue: 設定不備フローでは activityService.record が呼ばれない")
    void createIssue_doesNotRecordOnConfigError() {
        props.getGithub().setEnabled(false);
        assertThatThrownBy(() -> service.createIssue(REPORT_ID, ACTOR_ID))
                .isInstanceOf(BusinessException.class);
        verify(activityService, never()).record(anyLong(),
                any(), any(ErrorReportActivityType.class), any(), anyMap());
    }
}
