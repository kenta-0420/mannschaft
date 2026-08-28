package com.mannschaft.app.role.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.role.dto.InviteJoinRequest;
import com.mannschaft.app.role.service.InviteService;
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
 * {@link InviteController} の単体テスト。
 *
 * <p>InviteController#joinByInvite の自己スコープ性を固定する契約テスト。
 * {@code InviteService#joinByInvite} は参加者として {@code SecurityUtils.getCurrentUserId()}
 * のみを使用し、対象スコープは招待トークン自体（bearer capability）が決めるため、
 * 他人の識別子を参加者として指定する経路が構造的に無い。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InviteController 単体テスト")
class InviteControllerTest {

    @Mock
    private InviteService inviteService;

    @InjectMocks
    private InviteController controller;

    private static final Long USER_ID = 100L;
    private static final String TOKEN = "dummy-invite-token";

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
    @DisplayName("joinByInvite は SecurityUtils.getCurrentUserId() のみを参加者として渡す")
    void joinByInvite_boundToCurrentUserOnly() {
        controller.joinByInvite(TOKEN, new InviteJoinRequest(null));

        // 他人の userId を参加者として渡す経路が存在しないことの裏取り。
        verify(inviteService).joinByInvite(TOKEN, USER_ID, null);
    }
}
