package com.mannschaft.app.role.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.role.dto.PermissionCatalogItemResponse;
import com.mannschaft.app.role.service.PermissionCatalogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link PermissionCatalogController} の単体テスト。認可は ADMIN+（スコープを確認）で、権限がなければ一覧を返さない。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PermissionCatalogController 単体テスト")
class PermissionCatalogControllerTest {

    private static final Long USER_ID = 1L;

    @Mock private PermissionCatalogService service;
    @Mock private AccessControlService accessControlService;

    @InjectMocks
    private PermissionCatalogController controller;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("対象スコープの ADMIN+ は 200 で一覧を得る（認可は指定スコープで確認する）")
    void adminGetsCatalog() {
        given(service.list("TEAM")).willReturn(List.of(
                new PermissionCatalogItemResponse(1L, "MANAGE_ORG_AFFILIATION", "組織への加盟操作", "TEAM", true)));

        var response = controller.list("TEAM", 10L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).hasSize(1);
        verify(accessControlService).checkAdminOrAbove(USER_ID, 10L, "TEAM");
    }

    @Test
    @DisplayName("対象スコープの ADMIN+ でなければ 403 が伝播し、一覧は組み立てない")
    void nonAdminIsRejectedBeforeLookup() {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkAdminOrAbove(USER_ID, 10L, "TEAM");

        assertThatThrownBy(() -> controller.list("TEAM", 10L)).isInstanceOf(BusinessException.class);
        verify(service, never()).list("TEAM");
    }

    @Test
    @DisplayName("scopeType が TEAM / ORGANIZATION 以外なら認可チェックの前に 400")
    void rejectsUnsupportedScopeType() {
        assertThatThrownBy(() -> controller.list("PLATFORM", 10L)).isInstanceOf(BusinessException.class);
        verify(accessControlService, never()).checkAdminOrAbove(USER_ID, 10L, "PLATFORM");
    }
}
