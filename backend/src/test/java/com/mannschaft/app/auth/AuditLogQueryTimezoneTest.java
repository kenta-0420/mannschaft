package com.mannschaft.app.auth;

import com.mannschaft.app.auth.service.AuditLogQueryService;
import com.mannschaft.app.common.AccessControlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** JDBC の壁時計値と瞬間値を区別し、監査参照の入出力の日時境界を固定する。 */
@ExtendWith(MockitoExtension.class)
class AuditLogQueryTimezoneTest {

    private static final long OWNER = 930071L;
    private static final LocalDateTime JST = LocalDateTime.of(2026, 10, 19, 9, 49);
    private static final LocalDateTime UTC = LocalDateTime.of(2026, 10, 19, 0, 49);

    @Mock private AccessControlService accessControlService;
    @Mock private JdbcTemplate jdbcTemplate;
    @InjectMocks private AuditLogQueryService service;

    static Stream<Object> jdbcDatetimeValues() {
        return Stream.of(UTC, Timestamp.from(Instant.parse("2026-10-19T00:49:00Z")));
    }

    @ParameterizedTest
    @MethodSource("jdbcDatetimeValues")
    void JDBCのUTC壁時計またはTimestampの瞬間をアプリのJST壁時計へ戻す(Object jdbcValue) {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row(jdbcValue)));

        var result = service.getMyLogs(OWNER, null, null, null, null, null, 5);

        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().getFirst().getCreatedAt()).isEqualTo(JST);
    }

    @Test
    void JDBCのnull日時はnullを保つ() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row(null)));

        assertThat(service.getMyLogs(OWNER, null, null, null, null, null, 5)
                .getData().getFirst().getCreatedAt()).isNull();
    }

    @Test
    void 日時条件なしで0件なら空ページを返す() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        var result = service.getMyLogs(OWNER, null, null, null, null, null, 5);

        assertThat(result.getData()).isEmpty();
        assertThat(result.getMeta().isHasNext()).isFalse();
        assertThat(result.getMeta().getNextCursor()).isNull();
    }

    @Test
    void JSTの日付検索境界をUTC壁時計に変換して列に関数を掛けず束縛する() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        LocalDateTime from = LocalDateTime.of(2026, 10, 19, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 10, 19, 23, 59, 59);

        service.getMyLogs(OWNER, null, null, from, to, "17", 5);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> bindings = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(sql.capture(), bindings.capture());
        assertThat(sql.getValue()).contains("user_id = ?", "id < ?", "created_at >= ?", "created_at <= ?")
                .doesNotContain("CONVERT_TZ", "DATE(created_at)");
        assertThat(bindings.getValue()).containsExactly(OWNER, 17L,
                LocalDateTime.of(2026, 10, 18, 15, 0),
                LocalDateTime.of(2026, 10, 19, 14, 59, 59), 6);
    }

    private Map<String, Object> row(Object createdAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1L);
        row.put("user_id", OWNER);
        row.put("event_type", "LOGIN_SUCCESS");
        row.put("created_at", createdAt);
        return row;
    }
}
