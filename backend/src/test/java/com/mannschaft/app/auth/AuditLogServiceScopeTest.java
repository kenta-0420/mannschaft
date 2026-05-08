package com.mannschaft.app.auth;

import com.mannschaft.app.auth.dto.AuditLogResponse;
import com.mannschaft.app.auth.repository.AuditLogRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditLogService — スコープ付き参照（Phase 3）")
class AuditLogServiceScopeTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private AuditLogService auditLogService;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 100L;
    private static final Long ORG_ID = 200L;

    private Map<String, Object> buildRow(Long id, String eventType) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("user_id", USER_ID);
        row.put("target_user_id", null);
        row.put("team_id", TEAM_ID);
        row.put("organization_id", null);
        row.put("event_type", eventType);
        row.put("ip_address", "127.0.0.1");
        row.put("user_agent", "test-agent");
        row.put("session_hash", null);
        row.put("metadata", null);
        row.put("created_at", java.sql.Timestamp.valueOf(LocalDateTime.now()));
        return row;
    }

    @Nested
    @DisplayName("getTeamAuditLogs")
    class GetTeamAuditLogs {

        @Test
        @DisplayName("正常: チームADMINがログを取得できる")
        void ok() {
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                    .thenReturn(List.of(buildRow(1L, "TEAM_MEMBER_JOINED")));

            CursorPagedResponse<AuditLogResponse> result = auditLogService.getTeamAuditLogs(
                    USER_ID, TEAM_ID, null, null, null, null, null, null, 20);

            assertThat(result.getData()).hasSize(1);
            assertThat(result.getData().get(0).getEventType()).isEqualTo("TEAM_MEMBER_JOINED");
        }

        @Test
        @DisplayName("異常: ADMINでない場合は403を返す")
        void forbidden() {
            doThrow(new BusinessException(com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> auditLogService.getTeamAuditLogs(
                    USER_ID, TEAM_ID, null, null, null, null, null, null, 20))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("異常: from > to の場合は AUDIT_001 エラーコードで400を返す")
        void invalidDateRange() {
            LocalDateTime from = LocalDateTime.now();
            LocalDateTime to = from.minusDays(1);

            assertThatThrownBy(() -> auditLogService.getTeamAuditLogs(
                    USER_ID, TEAM_ID, null, null, null, from, to, null, 20))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(AuditLogErrorCode.INVALID_DATE_RANGE));
        }

        @Test
        @DisplayName("正常: hasNextが正しく判定される（limit+1件取得時）")
        void cursorPagination() {
            // safeLimit=2 なので3件返せば hasNext=true
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                    .thenReturn(List.of(
                            buildRow(3L, "TEAM_MEMBER_JOINED"),
                            buildRow(2L, "TEAM_MEMBER_JOINED"),
                            buildRow(1L, "TEAM_MEMBER_JOINED")
                    ));

            CursorPagedResponse<AuditLogResponse> result = auditLogService.getTeamAuditLogs(
                    USER_ID, TEAM_ID, null, null, null, null, null, null, 2);

            assertThat(result.getData()).hasSize(2);
            assertThat(result.getMeta().isHasNext()).isTrue();
            assertThat(result.getMeta().getNextCursor()).isEqualTo("2");
        }
    }

    @Nested
    @DisplayName("getOrganizationAuditLogs")
    class GetOrganizationAuditLogs {

        @Test
        @DisplayName("正常: 組織ADMINがログを取得できる")
        void ok() {
            Map<String, Object> row = buildRow(1L, "ORGANIZATION_MEMBER_JOINED");
            row.put("team_id", null);
            row.put("organization_id", ORG_ID);
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                    .thenReturn(List.of(row));

            CursorPagedResponse<AuditLogResponse> result = auditLogService.getOrganizationAuditLogs(
                    USER_ID, ORG_ID, null, null, null, null, null, null, 20);

            assertThat(result.getData()).hasSize(1);
            assertThat(result.getData().get(0).getEventType()).isEqualTo("ORGANIZATION_MEMBER_JOINED");
        }

        @Test
        @DisplayName("異常: from > to の場合は AUDIT_001 エラーコードで400を返す")
        void invalidDateRange() {
            LocalDateTime from = LocalDateTime.now();
            LocalDateTime to = from.minusHours(1);

            assertThatThrownBy(() -> auditLogService.getOrganizationAuditLogs(
                    USER_ID, ORG_ID, null, null, null, from, to, null, 20))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(AuditLogErrorCode.INVALID_DATE_RANGE));
        }
    }

    @Nested
    @DisplayName("maskSensitiveMetadata（getTeamAuditLogs 経由）")
    class MaskSensitiveMetadata {

        @Test
        @DisplayName("EMAIL_CHANGED イベントの new_email / old_email がマスクされる")
        void maskEmailChangedMetadata() {
            Map<String, Object> row = buildRow(1L, "EMAIL_CHANGED");
            row.put("metadata", "{\"old_email\":\"old@example.com\",\"new_email\":\"new@example.com\"}");
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                    .thenReturn(List.of(row));

            CursorPagedResponse<AuditLogResponse> result = auditLogService.getTeamAuditLogs(
                    USER_ID, TEAM_ID, null, null, null, null, null, null, 20);

            String metadata = result.getData().get(0).getMetadata();
            assertThat(metadata).doesNotContain("old@example.com");
            assertThat(metadata).doesNotContain("new@example.com");
            assertThat(metadata).contains("***");
        }

        @Test
        @DisplayName("TEAM_MEMBER_JOINED イベントの metadata はマスクされない")
        void noMaskForNonSensitiveEvent() {
            Map<String, Object> row = buildRow(1L, "TEAM_MEMBER_JOINED");
            row.put("metadata", "{\"role\":\"MEMBER\"}");
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                    .thenReturn(List.of(row));

            CursorPagedResponse<AuditLogResponse> result = auditLogService.getTeamAuditLogs(
                    USER_ID, TEAM_ID, null, null, null, null, null, null, 20);

            String metadata = result.getData().get(0).getMetadata();
            assertThat(metadata).isEqualTo("{\"role\":\"MEMBER\"}");
        }
    }
}
