package com.mannschaft.app.cspreport;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261001-0839: MIME型を含む実MVC・Security・Service・MySQLの受信契約。
 * Controllerの直接呼び出しでは検知できないconsumesの欠落を防ぐ。
 * Reporting API配列の保存対応は本契約の対象外であり、204で無視する既存挙動を検証する。
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CSPレポートContent-Type受信契約")
class CspReportContentTypeContractIT extends AbstractMySqlIntegrationTest {

    private static final String ENDPOINT = "/api/v1/security/csp-reports";
    private static final String[] CONTENT_TYPES = {
            "application/csp-report", "application/reports+json", "application/json"
    };

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private final String userAgent = "cmp2610010839-" + UUID.randomUUID();

    @AfterEach
    void 自分の受信行のみ削除する() {
        jdbc.update("DELETE FROM csp_reports WHERE user_agent = ?", userAgent);
    }

    static Stream<Arguments> 有効レポートの形式() {
        return Stream.of(CONTENT_TYPES).flatMap(type -> Stream.of(false, true)
                .flatMap(charset -> Stream.of(false, true).map(wrapper -> Arguments.of(
                        type + (charset ? ";charset=UTF-8" : ""), wrapper))));
    }

    static Stream<Arguments> 保存しない入力() {
        return Stream.of(CONTENT_TYPES).flatMap(type -> Stream.of(
                Arguments.of(type, null),
                Arguments.of(type, ""),
                Arguments.of(type, " \n\t "),
                Arguments.of(type, "null"),
                Arguments.of(type, "{broken"),
                Arguments.of(type, "[]"),
                Arguments.of(type, "[{\"type\":\"csp-violation\",\"body\":{}}]")));
    }

    @ParameterizedTest(name = "{0}, ラッパー={1}")
    @MethodSource("有効レポートの形式")
    @DisplayName("未認証で3種のMIME型とcharsetを受理し有効レポートを保存する")
    void 未認証の有効レポートは204で実DBへ保存される(String contentType, boolean wrapper) throws Exception {
        String documentUri = "https://cmp2610010839.example.test/" + UUID.randomUUID();
        String report = """
                {"document-uri":"%s","blocked-uri":"https://blocked.example.test/script.js",
                 "violated-directive":"script-src","effective-directive":"script-src-elem",
                 "disposition":"enforce","status-code":200}
                """.formatted(documentUri);
        String body = wrapper ? "{\"csp-report\":" + report + "}" : report;

        mockMvc.perform(request(contentType).content(body))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(保存件数()).isEqualTo(1L);
        Map<String, Object> saved = jdbc.queryForMap(
                "SELECT document_uri, blocked_uri, violated_directive, occurrence_count "
                        + "FROM csp_reports WHERE user_agent = ?", userAgent);
        assertThat(saved.get("document_uri")).isEqualTo(documentUri);
        assertThat(saved.get("blocked_uri")).isEqualTo("https://blocked.example.test/script.js");
        assertThat(saved.get("violated_directive")).isEqualTo("script-src");
        assertThat(((Number) saved.get("occurrence_count")).intValue()).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}, body={1}")
    @MethodSource("保存しない入力")
    @DisplayName("空・null・不正JSON・未対応配列は204で保存しない")
    void 保存しない入力も204で空応答を返す(String contentType, String body) throws Exception {
        MockHttpServletRequestBuilder request = request(contentType);
        if (body != null) {
            request.content(body);
        }
        mockMvc.perform(request)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(保存件数()).isZero();
    }

    private MockHttpServletRequestBuilder request(String contentType) {
        return post(ENDPOINT).servletPath(ENDPOINT)
                .contentType(contentType).header("User-Agent", userAgent);
    }

    private Long 保存件数() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM csp_reports WHERE user_agent = ?",
                Long.class, userAgent);
    }
}
