package com.mannschaft.app.cspreport;

import com.mannschaft.app.cspreport.dto.CspReportRequest;
import com.mannschaft.app.cspreport.service.CspReportService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CSP報告のUUIDv7永続化")
class CspReportUuidV7PersistenceTest extends AbstractMySqlIntegrationTest {

    private static final String DOCUMENT_URI = "https://cmp008.example.test/page";

    @Autowired
    private CspReportService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM csp_reports WHERE document_uri = ?", DOCUMENT_URI);
    }

    @Test
    @DisplayName("新規報告は16バイトUUIDv7で保存され、重複報告は同じ行に集約される")
    void 新規報告はUUIDv7で保存され重複報告は同じ行に集約される() {
        CspReportRequest report = CspReportRequest.builder()
                .documentUri(DOCUMENT_URI)
                .blockedUri("https://cmp008.example.test/blocked.js")
                .violatedDirective("script-src")
                .effectiveDirective("script-src-elem")
                .disposition("enforce")
                .statusCode(200)
                .build();

        service.receive(report, "127.0.0.1", "cmp008-test");
        Map<String, Object> first = jdbc.queryForMap(
                "SELECT HEX(id) AS id, OCTET_LENGTH(id) AS bytes, occurrence_count AS count "
                        + "FROM csp_reports WHERE document_uri = ?", DOCUMENT_URI);
        UUID id = uuidFromHex((String) first.get("id"));
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
        assertThat(((Number) first.get("bytes")).intValue()).isEqualTo(16);
        assertThat(((Number) first.get("count")).intValue()).isEqualTo(1);

        service.receive(report, "127.0.0.2", "cmp008-test-duplicate");
        Map<String, Object> second = jdbc.queryForMap(
                "SELECT HEX(id) AS id, occurrence_count AS count FROM csp_reports WHERE document_uri = ?",
                DOCUMENT_URI);
        assertThat(second.get("id")).isEqualTo(first.get("id"));
        assertThat(((Number) second.get("count")).intValue()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM csp_reports WHERE document_uri = ?", Long.class, DOCUMENT_URI))
                .isEqualTo(1L);
    }

    private static UUID uuidFromHex(String hex) {
        String value = hex.toLowerCase();
        return UUID.fromString(value.substring(0, 8) + "-" + value.substring(8, 12) + "-"
                + value.substring(12, 16) + "-" + value.substring(16, 20) + "-" + value.substring(20));
    }
}
