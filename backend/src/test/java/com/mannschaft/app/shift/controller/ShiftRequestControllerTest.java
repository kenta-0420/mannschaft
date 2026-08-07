package com.mannschaft.app.shift.controller;

import com.mannschaft.app.shift.dto.ShiftRequestResponse;
import com.mannschaft.app.shift.service.ShiftRequestService;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftRequestController} の単体テスト（{@code listMyRequests} の自己スコープ契約テストを兼ねる・
 * 認可根治戦役 Wave6 ロットC）。
 *
 * <p>{@code ShiftRequestService#listMyRequests} には常に認証主体の {@code USER_ID} のみが渡り、
 * 他ユーザーのシフト希望には到達できないことを固定する。
 * {@code ShiftRequestController#listMyRequests} の自己スコープ性を固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftRequestController 単体テスト")
class ShiftRequestControllerTest {

    private static final Long USER_ID = 1L;

    @Mock
    private ShiftRequestService requestService;

    @InjectMocks
    private ShiftRequestController controller;

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
    @DisplayName("listMyRequests: 認証主体自身の userId のみで自分のシフト希望を取得する"
            + "（ShiftRequestController#listMyRequests）")
    void listMyRequests_自己スコープ() {
        ShiftRequestResponse res = new ShiftRequestResponse(
                1L, 2L, USER_ID, 3L, LocalDate.of(2026, 6, 1), "PREFERRED", null, LocalDateTime.now());
        given(requestService.listMyRequests(USER_ID)).willReturn(List.of(res));

        assertThat(controller.listMyRequests().getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(requestService).listMyRequests(USER_ID);
    }
}
