package com.mannschaft.app.visibility.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.visibility.dto.CreateVisibilityTemplateRequest;
import com.mannschaft.app.visibility.dto.UpdateVisibilityTemplateRequest;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import com.mannschaft.app.visibility.service.VisibilityTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

/**
 * {@link VisibilityTemplateController} の単体テスト。
 *
 * <p>VisibilityTemplateController#listTemplates / #createTemplate の自己スコープ性、
 * および #getTemplate / #updateTemplate / #deleteTemplate の所有者検証委譲
 * （AuthorizedInService）を固定する契約テスト。いずれも {@code VisibilityTemplateService} が
 * {@code SecurityUtils.getCurrentUserId()} を owner として使用する
 * （getTemplate は findAccessibleById、update/delete は findByIdAndOwnerUserId で
 * 所有者一致を検証し、不一致は 404 で存在秘匿する。認可根治 Wave4 で client 供給の
 * ownerUserId 詐称 IDOR を是正済み）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VisibilityTemplateController 単体テスト")
class VisibilityTemplateControllerTest {

    @Mock
    private VisibilityTemplateService visibilityTemplateService;
    @Mock
    private VisibilityTemplateEvaluator visibilityTemplateEvaluator;

    @InjectMocks
    private VisibilityTemplateController controller;

    private static final Long USER_ID = 100L;
    private static final Long TEMPLATE_ID = 600L;

    private MockedStatic<SecurityUtils> securityUtils;

    @BeforeEach
    void setUpSecurityUtils() {
        securityUtils = Mockito.mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDownSecurityUtils() {
        securityUtils.close();
    }

    @Test
    @DisplayName("listTemplates は SecurityUtils.getCurrentUserId() のみを検索条件に渡す")
    void listTemplates_boundToCurrentUserOnly() {
        controller.listTemplates();

        verify(visibilityTemplateService).listTemplates(USER_ID);
    }

    @Test
    @DisplayName("createTemplate は SecurityUtils.getCurrentUserId() を owner として渡す")
    void createTemplate_boundToCurrentUserOnly() {
        CreateVisibilityTemplateRequest request = CreateVisibilityTemplateRequest.builder()
            .name("template")
            .rules(java.util.List.of())
            .build();

        controller.createTemplate(request);

        verify(visibilityTemplateService).createTemplate(request, USER_ID);
    }

    @Test
    @DisplayName("getTemplate は所有者検証を SecurityUtils.getCurrentUserId() で行う")
    void getTemplate_ownershipCheckedAgainstCurrentUser() {
        controller.getTemplate(TEMPLATE_ID);

        verify(visibilityTemplateService).getTemplate(TEMPLATE_ID, USER_ID);
    }

    @Test
    @DisplayName("updateTemplate は所有者検証を SecurityUtils.getCurrentUserId() で行う")
    void updateTemplate_ownershipCheckedAgainstCurrentUser() {
        UpdateVisibilityTemplateRequest request = UpdateVisibilityTemplateRequest.builder()
            .name("template")
            .rules(java.util.List.of())
            .build();

        controller.updateTemplate(TEMPLATE_ID, request);

        verify(visibilityTemplateService).updateTemplate(TEMPLATE_ID, request, USER_ID);
    }

    @Test
    @DisplayName("deleteTemplate は所有者検証を SecurityUtils.getCurrentUserId() で行う")
    void deleteTemplate_ownershipCheckedAgainstCurrentUser() {
        controller.deleteTemplate(TEMPLATE_ID);

        verify(visibilityTemplateService).deleteTemplate(TEMPLATE_ID, USER_ID);
    }
}
