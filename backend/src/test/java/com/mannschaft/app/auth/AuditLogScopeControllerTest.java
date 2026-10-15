package com.mannschaft.app.auth;

import com.mannschaft.app.auth.controller.AuditLogScopeController;
import com.mannschaft.app.auth.dto.AuditLogResponse;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditLogScopeController")
class AuditLogScopeControllerTest {

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private AuditLogScopeController controller;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 100L;
    private static final Long ORG_ID = 200L;

    private CursorPagedResponse<AuditLogResponse> emptyResponse() {
        return CursorPagedResponse.of(
                Collections.emptyList(),
                new CursorPagedResponse.CursorMeta(null, false, 20));
    }

    @Nested
    @DisplayName("GET /api/v1/teams/{teamId}/audit-logs")
    class TeamAuditLogs {

        @Test
        @DisplayName("正常: チームADMINがログ一覧を取得できる")
        void ok() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                when(auditLogService.getTeamAuditLogs(
                        eq(USER_ID), eq(TEAM_ID), isNull(),
                        isNull(), isNull(), isNull(), isNull(), isNull(), anyInt()))
                        .thenReturn(emptyResponse());

                CursorPagedResponse<AuditLogResponse> result =
                        controller.getTeamAuditLogs(TEAM_ID, null, null, null, null, null, null, 20);

                assertThat(result).isNotNull();
                assertThat(result.getData()).isEmpty();
            }
        }

        @Test
        @DisplayName("異常: ADMINでない場合は403を返す")
        void forbidden() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                when(auditLogService.getTeamAuditLogs(
                        any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                        .thenThrow(new BusinessException(CommonErrorCode.COMMON_002));

                assertThatThrownBy(() ->
                        controller.getTeamAuditLogs(TEAM_ID, null, null, null, null, null, null, 20))
                        .isInstanceOf(BusinessException.class);
            }
        }

        @Test
        @DisplayName("正常: eventType カンマ区切りで複数指定できる")
        void withEventTypeFilter() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                when(auditLogService.getTeamAuditLogs(
                        eq(USER_ID), eq(TEAM_ID), isNull(),
                        eq(List.of("TEAM_MEMBER_JOINED", "TEAM_MEMBER_REMOVED")),
                        isNull(), isNull(), isNull(), isNull(), anyInt()))
                        .thenReturn(emptyResponse());

                CursorPagedResponse<AuditLogResponse> result =
                        controller.getTeamAuditLogs(TEAM_ID, null,
                                "TEAM_MEMBER_JOINED,TEAM_MEMBER_REMOVED",
                                null, null, null, null, 20);

                assertThat(result).isNotNull();
            }
        }
    }

    @Nested
    @DisplayName("GET /api/v1/organizations/{orgId}/audit-logs")
    class OrganizationAuditLogs {

        @Test
        @DisplayName("正常: 組織ADMINがログ一覧を取得できる")
        void ok() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                when(auditLogService.getOrganizationAuditLogs(
                        eq(USER_ID), eq(ORG_ID), isNull(),
                        isNull(), isNull(), isNull(), isNull(), isNull(), anyInt()))
                        .thenReturn(emptyResponse());

                CursorPagedResponse<AuditLogResponse> result =
                        controller.getOrganizationAuditLogs(ORG_ID, null, null, null, null, null, null, 20);

                assertThat(result).isNotNull();
                assertThat(result.getData()).isEmpty();
            }
        }

        @Test
        @DisplayName("異常: ADMINでない場合は403を返す")
        void forbidden() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                when(auditLogService.getOrganizationAuditLogs(
                        any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                        .thenThrow(new BusinessException(CommonErrorCode.COMMON_002));

                assertThatThrownBy(() ->
                        controller.getOrganizationAuditLogs(ORG_ID, null, null, null, null, null, null, 20))
                        .isInstanceOf(BusinessException.class);
            }
        }
    }
}
