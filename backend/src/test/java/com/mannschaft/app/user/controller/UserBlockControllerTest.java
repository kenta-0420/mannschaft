package com.mannschaft.app.user.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.user.dto.BlockRequest;
import com.mannschaft.app.user.service.UserBlockService;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link UserBlockController} の単体テスト。
 *
 * <p>UserBlockController#block / #unblock / #listBlocks の自己スコープ性を固定する契約テスト。
 * いずれも {@code UserBlockService} が {@code SecurityUtils.getCurrentUserId()} のみを
 * ブロック行の主体として使用し、任意の他ユーザーを主体として偽装する経路が構造的に無い
 * （対象 blockedId は主体ではなく単なるブロック相手）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserBlockController 単体テスト")
class UserBlockControllerTest {

    @Mock
    private UserBlockService userBlockService;

    @InjectMocks
    private UserBlockController controller;

    private static final Long USER_ID = 100L;
    private static final Long BLOCKED_ID = 200L;

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
    @DisplayName("block は SecurityUtils.getCurrentUserId() のみを主体として渡す")
    void block_boundToCurrentUserOnly() {
        BlockRequest request = new BlockRequest();
        ReflectionTestUtils.setField(request, "blockedId", BLOCKED_ID);

        controller.block(request);

        // 他人の userId を主体として渡す経路が存在しないことの裏取り。
        verify(userBlockService).block(USER_ID, BLOCKED_ID);
    }

    @Test
    @DisplayName("unblock は SecurityUtils.getCurrentUserId() 自身のブロック行のみを解除する")
    void unblock_boundToCurrentUserOnly() {
        controller.unblock(BLOCKED_ID);

        // 他人の userId のブロック行を解除する経路が存在しないことの裏取り。
        verify(userBlockService).unblock(USER_ID, BLOCKED_ID);
    }

    @Test
    @DisplayName("listBlocks は SecurityUtils.getCurrentUserId() のみを検索条件に渡す")
    void listBlocks_boundToCurrentUserOnly() {
        given(userBlockService.listBlocks(USER_ID)).willReturn(List.of());

        controller.listBlocks();

        // 他人の userId を検索条件に渡す経路が存在しないことの裏取り。
        verify(userBlockService).listBlocks(USER_ID);
    }
}
