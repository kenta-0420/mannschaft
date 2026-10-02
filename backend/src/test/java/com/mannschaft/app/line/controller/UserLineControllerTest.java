package com.mannschaft.app.line.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.line.dto.LinkLineRequest;
import com.mannschaft.app.line.service.UserLineConnectionService;
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
 * {@link UserLineController} の単体テスト。
 *
 * <p>UserLineController#getStatus / #link / #unlink の自己スコープ性を固定する契約テスト。
 * {@code UserLineConnectionService} は {@code SecurityUtils.getCurrentUserId()} 自身にのみ
 * 作用し、他ユーザーの LINE 連携を操作・参照する経路が構造的に無い
 * （URL も {@code /api/v1/users/me/line} で対象IDを受け取らない）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserLineController 単体テスト")
class UserLineControllerTest {

    @Mock
    private UserLineConnectionService userLineConnectionService;

    @InjectMocks
    private UserLineController controller;

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
    @DisplayName("getStatus は SecurityUtils.getCurrentUserId() 自身の連携状態のみ参照する")
    void getStatus_boundToCurrentUserOnly() {
        controller.getStatus();

        verify(userLineConnectionService).getStatus(USER_ID);
    }

    @Test
    @DisplayName("link は SecurityUtils.getCurrentUserId() 自身にのみ紐付ける")
    void link_boundToCurrentUserOnly() {
        LinkLineRequest request = new LinkLineRequest("line-uid", null, null, null);

        controller.link(request);

        verify(userLineConnectionService).link(USER_ID, request);
    }

    @Test
    @DisplayName("unlink は SecurityUtils.getCurrentUserId() 自身の連携のみ解除する")
    void unlink_boundToCurrentUserOnly() {
        controller.unlink();

        verify(userLineConnectionService).unlink(USER_ID);
    }
}
