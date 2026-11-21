package com.mannschaft.app.cspreport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.cspreport.controller.CspReportController;
import com.mannschaft.app.cspreport.dto.CspReportRequest;
import com.mannschaft.app.cspreport.entity.CspReportEntity;
import com.mannschaft.app.cspreport.repository.CspReportRepository;
import com.mannschaft.app.cspreport.service.CspReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CSPのURL保存・集約・ログ境界を、外部へ渡す期待値で固定する回帰試験。 */
class CspReportPrivacyTest {
    private final CspReportRepository repository = mock(CspReportRepository.class);
    private final CspReportService service = new CspReportService(repository);
    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(CspReportService.class);
    private final Logger controllerLogger = (Logger) LoggerFactory.getLogger(CspReportController.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level originalServiceLevel;
    private Level originalControllerLevel;
    private CspReportEntity saved;
    private String lookupHash;

    @BeforeEach
    void ログを採取する() {
        originalServiceLevel = serviceLogger.getLevel();
        originalControllerLevel = controllerLogger.getLevel();
        serviceLogger.setLevel(Level.DEBUG);
        controllerLogger.setLevel(Level.DEBUG);
        logs.start();
        serviceLogger.addAppender(logs);
        controllerLogger.addAppender(logs);
        when(repository.findByReportHash(anyString())).thenAnswer(invocation -> {
            lookupHash = invocation.getArgument(0);
            return Optional.empty();
        });
        when(repository.save(any(CspReportEntity.class))).thenAnswer(invocation -> {
            saved = invocation.getArgument(0);
            return saved;
        });
    }

    @AfterEach
    void ロガーを復元する() {
        serviceLogger.detachAppender(logs);
        controllerLogger.detachAppender(logs);
        logs.stop();
        serviceLogger.setLevel(originalServiceLevel);
        controllerLogger.setLevel(originalControllerLevel);
    }

    @Test
    void URLの秘密部分を除いた同一値を保存とハッシュとログへ渡す() throws Exception {
        service.receive(CspReportRequest.builder()
                .documentUri("https://user:password@example.test:8443/a%2Fb?token=private-query#private-fragment")
                .blockedUri("http://another:password@cdn.example.test/a%20b.js?key=private-key#private-anchor")
                .violatedDirective("script-src").build(), "127.0.0.1", "privacy-test");

        assertThat(saved.getDocumentUri()).isEqualTo("https://example.test:8443/a%2Fb");
        assertThat(saved.getBlockedUri()).isEqualTo("http://cdn.example.test/a%20b.js");
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                "script-src|https://example.test:8443/a%2Fb|http://cdn.example.test/a%20b.js"
                        .getBytes(StandardCharsets.UTF_8)));
        assertThat(saved.getReportHash()).isEqualTo(expectedHash);
        assertThat(lookupHash).isEqualTo(expectedHash);
        assertThat(logText()).contains("https://example.test:8443/a%2Fb", "http://cdn.example.test/a%20b.js")
                .doesNotContain("password", "private-", "user:", "another:");
    }

    @ParameterizedTest
    @CsvSource({"'',''", "inline,inline", "eval,eval", "wasm-eval,wasm-eval", "about:blank,about:blank", "data:text/plain;private-payload,data:",
            "blob:https://example.test/private-identifier,blob:",
            "https://example.test/a%2fb,https://example.test/a%2fb", "https://example.test,https://example.test",
            "/local/path,/local/path", "https://user:private-password@example.test/a%zz?token=private-query,/invalid-uri"})
    void CSP分類と通常パスを保持し埋込み内容を残さない(String input, String expected) {
        service.receive(CspReportRequest.builder().documentUri(input).blockedUri(input).build(), null, null);

        assertThat(saved.getDocumentUri()).isEqualTo(expected);
        assertThat(saved.getBlockedUri()).isEqualTo(expected);
    }

    @Test
    void nullのURLはnullのまま保存する() {
        service.receive(CspReportRequest.builder().build(), null, null);

        assertThat(saved.getDocumentUri()).isNull();
        assertThat(saved.getBlockedUri()).isNull();
    }

    @Test
    void queryとfragmentの違いだけでは集約ハッシュを変えない() {
        service.receive(CspReportRequest.builder().documentUri("https://example.test/page?token=first#first")
                .blockedUri("https://cdn.example.test/script.js?key=first#first").build(), null, null);
        String firstHash = lookupHash;

        service.receive(CspReportRequest.builder().documentUri("https://example.test/page?token=second#second")
                .blockedUri("https://cdn.example.test/script.js?key=second#second").build(), null, null);

        assertThat(lookupHash).isEqualTo(firstHash);
    }

    @Test
    void 保存上限超過のURLは固定値にしてハッシュとログも揃える() throws Exception {
        String origin = "https://example.test/";
        String expected = "/invalid-uri";

        service.receive(CspReportRequest.builder().documentUri(origin + "a".repeat(1100))
                .blockedUri("inline").build(), null, null);

        assertThat(saved.getDocumentUri()).isEqualTo(expected);
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                ("|" + expected + "|inline").getBytes(StandardCharsets.UTF_8)));
        assertThat(lookupHash).isEqualTo(expectedHash);
        assertThat(logText()).contains(expected).doesNotContain("a".repeat(1001));
    }

    @Test
    void 上限ちょうどのencodedパスは保持し上限超過を途中で切断しない() {
        String origin = "https://example.test/";
        String boundary = origin + "a".repeat(1000 - origin.length() - 3) + "%2F";

        service.receive(CspReportRequest.builder().documentUri(boundary).blockedUri("inline").build(), null, null);
        assertThat(saved.getDocumentUri()).isEqualTo(boundary);

        String overflowing = origin + "a".repeat(999 - origin.length()) + "%2F";
        service.receive(CspReportRequest.builder().documentUri(overflowing).blockedUri("inline").build(), null, null);
        assertThat(saved.getDocumentUri()).isEqualTo("/invalid-uri");
    }

    @Test
    void 長さ判定より先にURLの秘密部分を除去する() {
        service.receive(CspReportRequest.builder()
                .documentUri("https://example.test/page?token=" + "x".repeat(2000))
                .blockedUri("https://user:" + "p".repeat(2000) + "@cdn.example.test/script.js")
                .build(), null, null);

        assertThat(saved.getDocumentUri()).isEqualTo("https://example.test/page");
        assertThat(saved.getBlockedUri()).isEqualTo("https://cdn.example.test/script.js");
        assertThat(logText()).doesNotContain("x".repeat(100), "p".repeat(100));
    }

    @Test
    void パース失敗の本文と例外本文をログへ残さず204を返す() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new CspReportController(service, new ObjectMapper())).build();

        mvc.perform(post("/api/v1/security/csp-reports").contentType("application/csp-report")
                        .content("{\"document-uri\":\"https://example.test/?token=private-body\",private-error"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/security/csp-reports").contentType("application/json").content("null"))
                .andExpect(status().isNoContent());

        assertThat(logText()).doesNotContain("private-body", "private-error", "document-uri", "https://", "body=null");
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    private String logText() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
    }
}
