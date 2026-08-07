package com.mannschaft.app.proxyvote.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.proxyvote.service.ProxyVoteSessionService;
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
 * {@link ProxyVoteSessionController} の単体テスト。
 *
 * <p>ProxyVoteSessionController#getMyHistory の自己スコープ性を固定する契約テスト。
 * {@code ProxyVoteSessionService#getMyHistory} は
 * {@code findByUserInvolvement(SecurityUtils.getCurrentUserId())} のみを検索条件にし、
 * 他ユーザーの投票・委任履歴を取得する経路が構造的に無い
 * （投票そのものは {@code ProxyVoteCastService#castVote} が
 * {@code checkMembership(currentUserId, ...)} でなりすましを防いでいる）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProxyVoteSessionController 単体テスト")
class ProxyVoteSessionControllerTest {

    @Mock
    private ProxyVoteSessionService sessionService;

    @InjectMocks
    private ProxyVoteSessionController controller;

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
    @DisplayName("getMyHistory は SecurityUtils.getCurrentUserId() のみを検索条件に渡す")
    void getMyHistory_boundToCurrentUserOnly() {
        org.mockito.BDDMockito.given(sessionService.getMyHistory(any(), any(), any()))
            .willReturn(Page.empty());

        controller.getMyHistory(null, 0, 20);

        verify(sessionService).getMyHistory(USER_ID, null, PageRequest.of(0, 20));
    }
}
