package com.mannschaft.app.role.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.organization.service.OrganizationQueryService;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import com.mannschaft.app.team.service.TeamQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 組織一覧のDB障害が空の成功レスポンスへ変換されないことをHTTP経由で検証する。 */
class MeOrganizationsFailureTest {

    @AfterEach
    void 認証を解除する() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 所属照会が失敗したら障害を伝播する() {
        var userRoles = mock(UserRoleRepository.class);
        var access = mock(AccessControlService.class);
        var controller = new MeController(userRoles, mock(RoleRepository.class), mock(TeamQueryService.class),
                mock(TeamOrgMembershipQueryService.class), mock(OrganizationQueryService.class), access,
                mock(MembershipBatchQueryService.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("1110", "", List.of()));
        when(userRoles.findByUserIdAndOrganizationIdIsNotNull(1110L)).thenReturn(List.of());
        when(access.findActiveMembershipJoinedAtByScope(1110L, "ORGANIZATION"))
                .thenThrow(new DataAccessResourceFailureException("所属照会失敗"));

        assertThatThrownBy(() -> mvc.perform(get("/api/v1/me/organizations")))
                .hasRootCauseInstanceOf(DataAccessResourceFailureException.class)
                .hasStackTraceContaining("所属照会失敗");
    }
}
