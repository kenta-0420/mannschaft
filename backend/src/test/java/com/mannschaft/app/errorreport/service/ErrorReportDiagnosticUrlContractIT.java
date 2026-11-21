package com.mannschaft.app.errorreport.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 診断URLの先行試練。実Security→Controller→Service→commit済みMySQLの値を観測する。
 * 正常受信・400・既存hash集約・legacy priority・HEALTH/BACKENDはcharacterizationで初回greenを期待する。
 * Redis/外部AI以外の自社Beanは置換せず、Docker未実行・起動失敗はred/greenに数えない。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ErrorReportDiagnosticUrlContractIT extends ErrorReportR1TestSupport {
    private static final String ENDPOINT = "/api/v1/error-reports";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;

    static Stream<Arguments> urls() {
        return Stream.of(
                Arguments.of("https://app.test/reset-password?token=QUERY#FRAGMENT", "https://app.test/reset-password"),
                Arguments.of("https://user:PASS@app.test/verify-email?unknown=QUERY#FRAGMENT", "https://app.test/verify-email"),
                Arguments.of("/verify-email?unknown=QUERY#FRAGMENT", "/verify-email"),
                Arguments.of("https://app.test", "https://app.test/"),
                Arguments.of("http://localhost:8081/p?token=QUERY", "http://localhost:8081/p"),
                Arguments.of("https://[::1]:8081/p?token=QUERY", "https://[::1]:8081/p"),
                Arguments.of("/a%3Fpart%23anchor?token=QUERY#FRAGMENT", "/a%3Fpart%23anchor"),
                Arguments.of("/normal/42/%E6%97%A5", "/normal/42/%E6%97%A5"),
                Arguments.of("//user:PASS@app.test/p?token=QUERY", "/invalid-url"),
                Arguments.of("ftp://user:PASS@app.test/p?token=QUERY", "/invalid-url"),
                Arguments.of("https:///missing-host?token=QUERY", "/invalid-url"),
                Arguments.of("https://app.test/%zz?token=QUERY", "/invalid-url"),
                Arguments.of("https://app.test:99999/p?token=QUERY", "/invalid-url"),
                Arguments.of("https://bad_host.test/p?token=QUERY", "/invalid-url"),
                Arguments.of("https://127.1/p?token=QUERY", "/invalid-url"),
                Arguments.of("https://app.test/p\r\n?token=QUERY", "/invalid-url"),
                Arguments.of("https://app.test\\p?token=QUERY", "/invalid-url"),
                Arguments.of("?token=QUERY", "/invalid-url"),
                Arguments.of("#FRAGMENT", "/invalid-url"),
                Arguments.of("relative?token=QUERY", "/invalid-url"));
    }

    @ParameterizedTest
    @MethodSource("urls")
    void AC2_匿名公開POSTは保存前にURLを除去する(String input, String expected) throws Exception {
        String message = "診断保存境界 " + UUID.randomUUID();
        Long id = submit(input, message, false, null);
        assertThat(storedUrl(id)).isEqualTo(expected);
        assertThat(submit(expected, message, false, null)).as("安全化済み値の再取込も同じhashへ集約").isEqualTo(id);
        assertThat(storedUrl(id)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT error_message FROM error_reports WHERE id = ?", String.class, id))
                .isEqualTo(message);
    }

    @Test
    void AC2_認証受信でもクライアントのBACKEND偽装で防御を回避できない() throws Exception {
        Long id = submit("https://user:PASS@app.test/p?secret=QUERY#FRAGMENT",
                "偽source " + UUID.randomUUID(), true, "BACKEND");
        assertThat(storedUrl(id)).isEqualTo("https://app.test/p");
    }

    static Stream<Arguments> legacyHashPaths() {
        return Stream.of(
                Arguments.of("https://app.test/", "/"),
                Arguments.of("https://app.test/cmp-diagnostic/hash-contract/42", "/cmp-diagnostic/hash-contract/42"),
                Arguments.of("https://app.test/cmp-diagnostic/hash-contract/a%3Fpart%23anchor",
                        "/cmp-diagnostic/hash-contract/a?part#anchor"));
    }

    @ParameterizedTest
    @MethodSource("legacyHashPaths")
    void AC3_通常browserと通常pathとencoded既存pathのhash入力を維持する(String url, String legacyPath) throws Exception {
        Long id = submit(url, "診断URL互換契約", false, null);
        assertThat(jdbc.queryForObject("SELECT error_hash FROM error_reports WHERE id = ?", String.class, id))
                .isEqualTo(sha256("診断URL互換契約|" + legacyPath));
    }

    static Stream<Arguments> legacySeverityInputs() {
        return Stream.of(
                Arguments.of("/cmp-diagnostic/normal?next=/payment&token=QUERY#FRAGMENT", "診断priority契約", "HIGH"),
                Arguments.of("/cmp-diagnostic/normal?next=/payment&token=QUERY#FRAGMENT", "ChunkLoadError 診断priority契約", "HIGH"),
                Arguments.of("/cmp-diagnostic/normal", "診断priority契約", "MEDIUM"),
                Arguments.of("/cmp-diagnostic/normal", "ChunkLoadError 診断priority契約", "LOW"));
    }

    @ParameterizedTest
    @MethodSource("legacySeverityInputs")
    void AC4_legacy入力のpriority順序とFEcanonical入力を区別して保存する(String url, String message, String severity) throws Exception {
        Long id = submit(url, message, false, null);
        assertThat(storedUrl(id)).isEqualTo("/cmp-diagnostic/normal");
        assertThat(jdbc.queryForObject("SELECT severity FROM error_reports WHERE id = ?", String.class, id))
                .isEqualTo(severity);
    }

    @Test
    void AC3_通常URLとquery違いは同じhashへ集約し解決後は再発する() throws Exception {
        String path = "/cmp-diagnostic/" + UUID.randomUUID();
        String message = "集約再発 " + UUID.randomUUID();
        Long first = submit("https://app.test" + path, message, false, null);
        String originalHash = jdbc.queryForObject("SELECT error_hash FROM error_reports WHERE id = ?", String.class, first);
        Long second = submit("https://app.test" + path + "?token=QUERY#FRAGMENT", message, false, null);
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT occurrence_count FROM error_reports WHERE id = ?", Integer.class, first)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT error_hash FROM error_reports WHERE id = ?", String.class, first)).isEqualTo(originalHash);
        tx.executeWithoutResult(state -> reports.findById(first).orElseThrow().setStatus(ErrorReportStatus.RESOLVED));
        assertThat(submit("https://app.test" + path + "?other=NEXT", message, false, null)).isEqualTo(first);
        assertThat(reload(first).getStatus()).isEqualTo(ErrorReportStatus.REOPENED);
        assertThat(storedUrl(first)).isEqualTo("https://app.test" + path);
    }

    @Test
    void AC4_null空白は従来どおり400で保存しない() throws Exception {
        for (String input : new String[] {null, "", "   "}) {
            var body = body(input, "無効DTO " + UUID.randomUUID());
            mvc.perform(post(ENDPOINT).servletPath(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(body))).andExpect(status().isBadRequest());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM error_reports WHERE request_id = ?", Integer.class,
                    body.get("requestId"))).isZero();
        }
    }

    @Test
    void AC4_HEALTHとBACKENDの内部path契約は保持する() {
        String component = "cmp-diagnostic-" + UUID.randomUUID();
        var health = service.findOrCreateHealthDownReport(component);
        owned.add(health);
        assertThat(storedUrl(health)).isEqualTo("health:" + component);
        var exception = new IllegalStateException("内部境界 " + UUID.randomUUID());
        var report = backend.doRecordBackendException(exception, "/api/v1/items/{itemId}", null,
                "127.0.0.211", requestId(), ErrorReportSeverity.MEDIUM);
        owned.add(report.getId());
        assertThat(storedUrl(report.getId())).isEqualTo("/api/v1/items/{itemId}");
    }

    private Long submit(String url, String message, boolean authenticated, String forgedSource) throws Exception {
        var body = body(url, message);
        if (forgedSource != null) {
            body.put("source", forgedSource);
            body.put("sourceType", forgedSource);
        }
        var request = post(ENDPOINT).servletPath(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", body.get("requestId"))
                .content(mapper.writeValueAsString(body));
        if (authenticated) {
            request.with(authentication(new UsernamePasswordAuthenticationToken("991231", "unused", List.of())));
        }
        var response = mvc.perform(request).andExpect(status().isCreated()).andReturn().getResponse();
        Long id = mapper.readTree(response.getContentAsString()).path("id").longValue();
        owned.add(id);
        return id;
    }

    private LinkedHashMap<String, Object> body(String url, String message) {
        var body = new LinkedHashMap<String, Object>();
        body.put("pageUrl", url);
        body.put("errorMessage", message);
        body.put("occurredAt", "2026-11-16T12:00:00");
        body.put("requestId", requestId());
        return body;
    }

    private String storedUrl(Long id) {
        return jdbc.queryForObject("SELECT page_url FROM error_reports WHERE id = ?", String.class, id);
    }
}
