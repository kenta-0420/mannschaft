package com.mannschaft.app.parking.controller;

import com.mannschaft.app.parking.dto.StripeConnectStatusResponse;
import com.mannschaft.app.parking.service.StripeConnectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link StripeConnectController} の単体テスト（自己スコープ契約テストを兼ねる・認可根治戦役 Wave6 ロットC）。
 *
 * <p>両 EP（startOnboarding/getStatus）はいずれも {@code StripeConnectService} へ
 * 認証主体の {@code USER_ID} のみを渡し、他ユーザーの ID を受け取る経路がエンドポイントに
 * 存在しないことを固定する。
 * {@code StripeConnectController#startOnboarding} / {@code StripeConnectController#getStatus}
 * の自己スコープ性を固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StripeConnectController 単体テスト")
class StripeConnectControllerTest {

    private static final Long USER_ID = 1L;

    @Mock
    private StripeConnectService stripeConnectService;

    @InjectMocks
    private StripeConnectController controller;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("startOnboarding: 認証主体自身の userId でオンボーディングURLを取得する"
            + "（StripeConnectController#startOnboarding）")
    void startOnboarding_自己スコープ() {
        given(stripeConnectService.startOnboarding(USER_ID)).willReturn("https://connect.stripe.com/onboarding");

        assertThat(controller.startOnboarding().getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(stripeConnectService).startOnboarding(USER_ID);
    }

    @Test
    @DisplayName("getStatus: 認証主体自身の userId でステータスを取得する（StripeConnectController#getStatus）")
    void getStatus_自己スコープ() {
        StripeConnectStatusResponse res =
                new StripeConnectStatusResponse(USER_ID, "acct_123", true, true, true);
        given(stripeConnectService.getStatus(USER_ID)).willReturn(res);

        assertThat(controller.getStatus().getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(stripeConnectService).getStatus(USER_ID);
    }
}
