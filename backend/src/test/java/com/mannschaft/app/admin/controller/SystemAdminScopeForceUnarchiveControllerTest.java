package com.mannschaft.app.admin.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.role.service.RoleSuccessionService;
import com.mannschaft.app.team.service.TeamService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 柱①「ADMINゼロ根治」AC8 — {@link SystemAdminScopeForceUnarchiveController} の受け入れテスト。
 *
 * <p>正本: docs/architecture/account_purge_last_admin_succession.md §10.12 / §15。
 * 「候補ゼロ→archive。SYSTEM_ADMINのforce-unarchiveはADMIN指名を伴わない限り拒否される」。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SystemAdminScopeForceUnarchiveController 受け入れテスト（AC8・柱①ADMINゼロ根治）")
class SystemAdminScopeForceUnarchiveControllerTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    private static final Long SYSTEM_ADMIN_ID = 1L;
    private static final Long SCOPE_ID = 100L;
    private static final Long NEW_ADMIN_ID = 2L;

    @Mock
    private AccessControlService accessControlService;
    @Mock
    private TeamService teamService;
    @Mock
    private OrganizationService organizationService;
    @Mock
    private RoleSuccessionService roleSuccessionService;

    @InjectMocks
    private SystemAdminScopeForceUnarchiveController controller;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        validatorFactory.close();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    @Test
    @DisplayName("AC8: newAdminUserIdを指名しないリクエストはBean Validationで拒否される")
    void ADMIN指名なしのリクエストはバリデーション違反になる() {
        SystemAdminScopeForceUnarchiveController.ForceUnarchiveRequest req =
                new SystemAdminScopeForceUnarchiveController.ForceUnarchiveRequest();
        // newAdminUserId を設定しない（未指名）

        Set<ConstraintViolation<SystemAdminScopeForceUnarchiveController.ForceUnarchiveRequest>> violations =
                validator.validate(req);

        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("AC8: newAdminUserIdを指名したリクエストはBean Validationを通過する")
    void ADMIN指名ありのリクエストはバリデーション違反にならない() {
        SystemAdminScopeForceUnarchiveController.ForceUnarchiveRequest req =
                new SystemAdminScopeForceUnarchiveController.ForceUnarchiveRequest();
        req.setNewAdminUserId(NEW_ADMIN_ID);

        Set<ConstraintViolation<SystemAdminScopeForceUnarchiveController.ForceUnarchiveRequest>> violations =
                validator.validate(req);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("AC8: SYSTEM_ADMINがADMIN指名ありでforce-unarchiveすると、unarchiveと初期ADMIN指名が同一操作内で成立する")
    void ADMIN指名ありのforceUnarchiveはunarchiveと昇格を両方実行する() {
        setAuth(SYSTEM_ADMIN_ID);
        SystemAdminScopeForceUnarchiveController.ForceUnarchiveRequest req =
                new SystemAdminScopeForceUnarchiveController.ForceUnarchiveRequest();
        req.setNewAdminUserId(NEW_ADMIN_ID);

        controller.forceUnarchive("TEAM", SCOPE_ID, req);

        verify(accessControlService).checkSystemAdmin(SYSTEM_ADMIN_ID);
        verify(teamService).unarchiveTeam(SCOPE_ID);
        verify(roleSuccessionService).forceAssignInitialAdminOnUnarchive(
                SCOPE_ID, "TEAM", NEW_ADMIN_ID, SYSTEM_ADMIN_ID);
        verify(organizationService, never()).unarchiveOrganization(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("AC8: SYSTEM_ADMIN以外はaccessControlServiceが例外を投げ、unarchive自体が実行されない")
    void SYSTEM_ADMIN以外は拒否されunarchiveされない() {
        setAuth(SYSTEM_ADMIN_ID);
        SystemAdminScopeForceUnarchiveController.ForceUnarchiveRequest req =
                new SystemAdminScopeForceUnarchiveController.ForceUnarchiveRequest();
        req.setNewAdminUserId(NEW_ADMIN_ID);
        org.mockito.BDDMockito.willThrow(new com.mannschaft.app.common.BusinessException(
                        com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                .given(accessControlService).checkSystemAdmin(SYSTEM_ADMIN_ID);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.forceUnarchive("TEAM", SCOPE_ID, req))
                .isInstanceOf(com.mannschaft.app.common.BusinessException.class);

        verify(teamService, never()).unarchiveTeam(eq(SCOPE_ID));
        verify(roleSuccessionService, times(0)).forceAssignInitialAdminOnUnarchive(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
