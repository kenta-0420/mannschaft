package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.dto.CreateFeedbackRequest;
import com.mannschaft.app.admin.service.FeedbackService;
import com.mannschaft.app.common.SecurityUtils;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link FeedbackController} の単体テスト。
 *
 * <p>FeedbackController#createFeedback / #getMyFeedbacks の自己スコープ性、
 * および #vote / #unvote の存在検証（AuthorizedInService）委譲を固定する契約テスト。
 * {@code FeedbackService} は {@code SecurityUtils.getCurrentUserId()} を主体として使用し、
 * vote/unvote は feedbackId の実在チェック（existsById）と本人の投票行（userId 単位）のみを
 * 操作する（他人の投票を代理で取り消す経路が構造的に無い）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeedbackController 単体テスト")
class FeedbackControllerTest {

    @Mock
    private FeedbackService feedbackService;

    @InjectMocks
    private FeedbackController controller;

    private static final Long USER_ID = 100L;
    private static final Long FEEDBACK_ID = 700L;

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
    @DisplayName("createFeedback は SecurityUtils.getCurrentUserId() を submittedBy として渡す")
    void createFeedback_boundToCurrentUserOnly() {
        CreateFeedbackRequest request =
            new CreateFeedbackRequest("GENERAL", null, "cat", "title", "body", false);
        given(feedbackService.createFeedback(any(), any())).willReturn(null);

        controller.createFeedback(request);

        verify(feedbackService).createFeedback(request, USER_ID);
    }

    @Test
    @DisplayName("getMyFeedbacks は SecurityUtils.getCurrentUserId() のみを検索条件に渡す")
    void getMyFeedbacks_boundToCurrentUserOnly() {
        given(feedbackService.getMyFeedbacks(any(), any()))
            .willReturn(Page.empty());

        controller.getMyFeedbacks(PageRequest.of(0, 20));

        verify(feedbackService).getMyFeedbacks(USER_ID, PageRequest.of(0, 20));
    }

    @Test
    @DisplayName("vote は SecurityUtils.getCurrentUserId() を投票主体として渡す")
    void vote_boundToCurrentUserOnly() {
        controller.vote(FEEDBACK_ID);

        verify(feedbackService).vote(FEEDBACK_ID, USER_ID);
    }

    @Test
    @DisplayName("unvote は SecurityUtils.getCurrentUserId() 自身の投票のみ取り消す")
    void unvote_boundToCurrentUserOnly() {
        controller.unvote(FEEDBACK_ID);

        verify(feedbackService).unvote(FEEDBACK_ID, USER_ID);
    }
}
