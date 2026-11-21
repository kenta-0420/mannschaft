package com.mannschaft.app.cspreport;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 匿名CSPレポートの実TCP受信から実MySQL保存・重複集約までを確認する。
 * MockMvcでは検査しないHTTPクライアント往復も含めるためRANDOM_PORTで起動する。
 * Redis等の外部依存設定とsingleton MySQLは共通基底を継承する。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class CspReportPrivacyHttpIT extends AbstractMySqlIntegrationTest {
    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;
    private final String userAgent = "csp-privacy-" + UUID.randomUUID();
    private final String path = "/privacy/" + UUID.randomUUID() + "/a%2Fb";

    @AfterEach
    void 自分の受信行だけを削除する() {
        jdbc.update("DELETE FROM csp_reports WHERE user_agent = ?", userAgent);
    }

    @Test
    void 未認証の実HTTP受信でURLの秘密部分を保存せず同じ違反に集約する() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/csp-report");
        headers.set("User-Agent", userAgent);

        for (String suffix : new String[]{"first", "second"}) {
            String body = """
                    {"csp-report":{"document-uri":"https://user:private-password@example.test%s?token=private-%s#private-anchor",
                    "blocked-uri":"https://cdn.example.test/script.js?key=private-%s#private-fragment",
                    "violated-directive":"script-src","disposition":"enforce"}}
                    """.formatted(path, suffix, suffix);
            ResponseEntity<String> response = http.postForEntity("/api/v1/security/csp-reports",
                    new HttpEntity<>(body, headers), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(response.getBody()).isNull();
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM csp_reports WHERE user_agent = ?",
                Long.class, userAgent)).isEqualTo(1L);
        Map<String, Object> saved = jdbc.queryForMap(
                "SELECT document_uri, blocked_uri, occurrence_count FROM csp_reports WHERE user_agent = ?", userAgent);
        assertThat(saved.get("document_uri")).isEqualTo("https://example.test" + path);
        assertThat(saved.get("blocked_uri")).isEqualTo("https://cdn.example.test/script.js");
        assertThat(((Number) saved.get("occurrence_count")).intValue()).isEqualTo(2);
    }
}
