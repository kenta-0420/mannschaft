package com.mannschaft.app.shift.controller;

import com.mannschaft.app.shift.dto.AvailabilityDefaultResponse;
import com.mannschaft.app.shift.dto.BulkAvailabilityDefaultRequest;
import com.mannschaft.app.shift.service.ShiftAvailabilityService;
import com.mannschaft.app.shift.service.ShiftHourlyRateService;
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

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftAvailabilityController} の勤務可能時間 3 EP の単体テスト
 * （自己スコープ契約テストを兼ねる・認可根治戦役 Wave6 ロットC）。
 *
 * <p>get/set/delete いずれも {@code ShiftAvailabilityService} へ認証主体の {@code USER_ID} のみを
 * userId として渡す（teamId は絞り込みにのみ働き、他ユーザーの勤務可能時間には到達不能）ことを固定する。
 * {@code ShiftAvailabilityController#getAvailabilityDefaults} /
 * {@code ShiftAvailabilityController#setAvailabilityDefaults} /
 * {@code ShiftAvailabilityController#deleteAvailabilityDefaults} の自己スコープ性を固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftAvailabilityController 単体テスト")
class ShiftAvailabilityControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;

    @Mock
    private ShiftAvailabilityService availabilityService;

    @Mock
    private ShiftHourlyRateService hourlyRateService;

    @InjectMocks
    private ShiftAvailabilityController controller;

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
    @DisplayName("getAvailabilityDefaults: 認証主体自身の userId のみで取得する"
            + "（ShiftAvailabilityController#getAvailabilityDefaults）")
    void getAvailabilityDefaults_自己スコープ() {
        AvailabilityDefaultResponse res = new AvailabilityDefaultResponse(
                1L, USER_ID, TEAM_ID, 1, LocalTime.of(9, 0), LocalTime.of(17, 0), "PREFERRED", null);
        given(availabilityService.getAvailabilityDefaults(USER_ID, TEAM_ID)).willReturn(List.of(res));

        assertThat(controller.getAvailabilityDefaults(TEAM_ID).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(availabilityService).getAvailabilityDefaults(USER_ID, TEAM_ID);
    }

    @Test
    @DisplayName("setAvailabilityDefaults: 認証主体自身の userId のみで一括設定する"
            + "（ShiftAvailabilityController#setAvailabilityDefaults）")
    void setAvailabilityDefaults_自己スコープ() {
        BulkAvailabilityDefaultRequest req = new BulkAvailabilityDefaultRequest(List.of());
        given(availabilityService.setAvailabilityDefaults(USER_ID, TEAM_ID, req)).willReturn(List.of());

        assertThat(controller.setAvailabilityDefaults(TEAM_ID, req).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(availabilityService).setAvailabilityDefaults(USER_ID, TEAM_ID, req);
    }

    @Test
    @DisplayName("deleteAvailabilityDefaults: 認証主体自身の userId のみで削除する"
            + "（ShiftAvailabilityController#deleteAvailabilityDefaults）")
    void deleteAvailabilityDefaults_自己スコープ() {
        assertThat(controller.deleteAvailabilityDefaults(TEAM_ID).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        verify(availabilityService).deleteAvailabilityDefaults(USER_ID, TEAM_ID);
    }
}
