package com.mannschaft.app.errorreport.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.errorreport.ErrorReportErrorCode;
import com.mannschaft.app.errorreport.ErrorReportProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * F12.5 Phase 2-C — {@link ErrorReportClaudeAiProvider} の単体テスト。
 *
 * <p>Tool Use レスポンスのパース、外部 URL サニタイズ、HTML タグ剥奪を検証する。</p>
 */
@DisplayName("ErrorReportClaudeAiProvider 単体テスト")
class ErrorReportClaudeAiProviderTest {

    private ErrorReportProperties props;
    private ObjectMapper objectMapper;
    private ErrorReportClaudeAiProvider provider;

    @BeforeEach
    void setUp() {
        props = new ErrorReportProperties();
        props.getAi().setModel("claude-haiku-4-5");
        props.getAi().setMaxTokens(1500);
        props.getAi().setTemperature(0.2);
        objectMapper = new ObjectMapper();
        provider = new ErrorReportClaudeAiProvider(props, objectMapper);
    }

    @Test
    @DisplayName("WebClient 未初期化（API キー未設定）→ ERROR_REPORT_007")
    void analyze_throwsWhenWebClientNotInitialized() {
        SanitizedErrorContext ctx = sampleContext();
        assertThatThrownBy(() -> provider.analyze(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorReportErrorCode.ERROR_REPORT_007.getMessage());
    }

    @Test
    @DisplayName("Tool Use レスポンスが正しくパースされる")
    void analyze_parsesToolUseResponse() {
        String response = """
                {
                  "id": "msg_1",
                  "type": "message",
                  "usage": {"input_tokens": 100, "output_tokens": 50},
                  "content": [{
                    "type": "tool_use",
                    "name": "analyze_frontend_error",
                    "input": {
                      "estimated_cause": "TypeError: Cannot read property of undefined",
                      "fix_proposal": "Add null check before accessing the property",
                      "impact_assessment": "影響限定的",
                      "suggested_files": ["src/components/A.vue", "src/utils/B.ts"]
                    }
                  }]
                }
                """;
        WebClient webClient = mockWebClient(response);
        provider.setWebClientForTest(webClient);

        AiAnalysisResult result = provider.analyze(sampleContext());

        assertThat(result.getEstimatedCause()).contains("TypeError");
        assertThat(result.getFixProposal()).contains("null check");
        assertThat(result.getImpactAssessment()).isEqualTo("影響限定的");
        assertThat(result.getSuggestedFiles())
                .containsExactly("src/components/A.vue", "src/utils/B.ts");
        assertThat(result.getPromptTokens()).isEqualTo(100);
        assertThat(result.getCompletionTokens()).isEqualTo(50);
    }

    @Test
    @DisplayName("外部 URL の Markdown リンクは [text] に縮約される")
    void sanitizeOutput_strippsExternalMarkdownLinks() {
        String result = provider.sanitizeOutput(
                "詳細は [こちら](https://evil.example.com/foo) を参照");
        assertThat(result).contains("[こちら]")
                .doesNotContain("evil.example.com");
    }

    @Test
    @DisplayName("自ドメイン URL のリンクは保持される")
    void sanitizeOutput_keepsSelfDomainLinks() {
        String result = provider.sanitizeOutput(
                "[管理画面](https://app.mannschaft.com/x) を確認");
        assertThat(result).contains("https://app.mannschaft.com/x");
    }

    @Test
    @DisplayName("script タグは除去される")
    void sanitizeOutput_stripsScriptTag() {
        String result = provider.sanitizeOutput(
                "<script>alert('xss')</script>原因はこれです");
        assertThat(result).doesNotContain("<script>")
                .doesNotContain("alert");
    }

    @Test
    @DisplayName("javascript: スキームは除去される")
    void sanitizeOutput_stripsJsScheme() {
        String result = provider.sanitizeOutput("[link](javascript:alert(1))");
        assertThat(result.toLowerCase()).doesNotContain("javascript:");
    }

    @Test
    @DisplayName("error フィールド付きレスポンスは例外を投げる")
    void analyze_throwsOnApiError() {
        WebClient webClient = mockWebClient(
                "{\"error\":{\"message\":\"rate limited\"}}");
        provider.setWebClientForTest(webClient);

        assertThatThrownBy(() -> provider.analyze(sampleContext()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("rate limited");
    }

    @Test
    @DisplayName("tool_use ブロックが無い場合は例外")
    void analyze_throwsWhenToolUseMissing() {
        WebClient webClient = mockWebClient(
                "{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}");
        provider.setWebClientForTest(webClient);

        assertThatThrownBy(() -> provider.analyze(sampleContext()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("tool_use");
    }

    private SanitizedErrorContext sampleContext() {
        return SanitizedErrorContext.builder()
                .errorMessage("TypeError")
                .stackTrace("at foo (bar.js:1)")
                .pageUrlPath("/teams/[ID]")
                .firstOccurredAt(LocalDateTime.now())
                .lastOccurredAt(LocalDateTime.now())
                .occurrenceCount(5)
                .affectedUserCount(2)
                .recentUserComments(List.of("コメント1"))
                .build();
    }

    /**
     * WebClient のレスポンスをモックする。
     * post().bodyValue(...).retrieve().bodyToMono(String.class).block() を responseBody で固定する。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private WebClient mockWebClient(String responseBody) {
        WebClient webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
        given(webClient.post()).willReturn(uriSpec);
        given(uriSpec.bodyValue(any())).willReturn((WebClient.RequestHeadersSpec) headersSpec);
        given(headersSpec.retrieve()).willReturn(responseSpec);
        given(responseSpec.bodyToMono(String.class)).willReturn(Mono.just(responseBody));
        return webClient;
    }
}
