package com.mannschaft.app.membership.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.membership.dto.SelfCheckinRequest;
import com.mannschaft.app.membership.service.MemberCardService;
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
 * {@link MemberCardController} の単体テスト。
 *
 * <p>MemberCardController#getMyCards / #selfCheckin の自己スコープ性を固定する契約テスト。
 * いずれも {@code MemberCardService} が {@code SecurityUtils.getCurrentUserId()} のみを
 * 検索条件（会員証所有者の特定）に束縛するため、他人の会員証へ到達する経路が構造的に無い。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemberCardController 単体テスト")
class MemberCardControllerTest {

    @Mock
    private MemberCardService memberCardService;

    @InjectMocks
    private MemberCardController controller;

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
    @DisplayName("getMyCards は SecurityUtils.getCurrentUserId() のみを検索条件に渡す")
    void getMyCards_boundToCurrentUserOnly() {
        controller.getMyCards();

        // 他人の userId を検索条件に渡す経路が存在しないことの裏取り。
        verify(memberCardService).getMyCards(USER_ID);
    }

    @Test
    @DisplayName("selfCheckin は SecurityUtils.getCurrentUserId() のみを本人特定に渡す")
    void selfCheckin_boundToCurrentUserOnly() {
        SelfCheckinRequest request = new SelfCheckinRequest("dummy-location-token");

        controller.selfCheckin(request);

        // 他人の userId を本人特定に渡す経路が存在しないことの裏取り。
        verify(memberCardService).selfCheckin(request, USER_ID);
    }
}
