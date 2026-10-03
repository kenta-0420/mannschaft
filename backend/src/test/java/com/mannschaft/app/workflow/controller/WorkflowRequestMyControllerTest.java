package com.mannschaft.app.workflow.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.workflow.service.WorkflowRequestService;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * {@link WorkflowRequestMyController} の単体テスト。
 *
 * <p>WorkflowRequestMyController#listMyRequests の自己スコープ性を固定する契約テスト。
 * {@code WorkflowRequestService#listMyRequests} は
 * {@code findByRequestedByOrderByCreatedAtDesc(SecurityUtils.getCurrentUserId())}
 * のみを組織横断で検索する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowRequestMyController 単体テスト")
class WorkflowRequestMyControllerTest {

    @Mock
    private WorkflowRequestService requestService;

    @InjectMocks
    private WorkflowRequestMyController controller;

    private static final Long USER_ID = 100L;

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
    @DisplayName("listMyRequests は SecurityUtils.getCurrentUserId() のみを検索条件に渡す")
    void listMyRequests_boundToCurrentUserOnly() {
        org.mockito.BDDMockito.given(requestService.listMyRequests(any(), any(), any()))
            .willReturn(Page.empty());

        controller.listMyRequests(null, 0, 20);

        verify(requestService).listMyRequests(USER_ID, null, PageRequest.of(0, 20));
    }
}
