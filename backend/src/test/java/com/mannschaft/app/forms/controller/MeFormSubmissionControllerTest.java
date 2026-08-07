package com.mannschaft.app.forms.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.forms.service.FormSubmissionService;
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
 * {@link MeFormSubmissionController} の単体テスト。
 *
 * <p>MeFormSubmissionController#listMySubmissions の自己スコープ性を固定する契約テスト。
 * {@code FormSubmissionService#listMySubmissions(userId,pageable)} は
 * {@code SecurityUtils.getCurrentUserId()} の submitted_by 絞り込みのみで全スコープを検索する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MeFormSubmissionController 単体テスト")
class MeFormSubmissionControllerTest {

    @Mock
    private FormSubmissionService submissionService;

    @InjectMocks
    private MeFormSubmissionController controller;

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
    @DisplayName("listMySubmissions は SecurityUtils.getCurrentUserId() のみを検索条件に渡す")
    void listMySubmissions_boundToCurrentUserOnly() {
        org.mockito.BDDMockito.given(submissionService.listMySubmissions(any(), any()))
            .willReturn(Page.empty());

        controller.listMySubmissions(0, 20);

        verify(submissionService).listMySubmissions(USER_ID, PageRequest.of(0, 20));
    }
}
