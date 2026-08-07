package com.mannschaft.app.timeline.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.timeline.dto.MuteRequest;
import com.mannschaft.app.timeline.dto.MuteResponse;
import com.mannschaft.app.timeline.service.TimelineMuteService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

/**
 * {@link TimelineMuteController} の単体テスト。
 *
 * <p>TimelineMuteController#addMute / #removeMute / #getMutes の自己スコープ性を
 * 固定する契約テスト。サービス層のリポジトリクエリ・更新はいずれも
 * {@code SecurityUtils.getCurrentUserId()} のみを主体として束縛するため、
 * 他人のミュート設定へ到達する経路が構造的に無い（{@code mutedId} はミュート対象の識別子であり、
 * 検索・更新の主体ではない）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimelineMuteController 単体テスト")
class TimelineMuteControllerTest {

    @Mock
    private TimelineMuteService muteService;

    @InjectMocks
    private TimelineMuteController controller;

    private static final Long USER_ID = 100L;
    private static final Long MUTED_ID = 55L;

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
    @DisplayName("addMute は SecurityUtils.getCurrentUserId() のみをミュート主体として渡す")
    void addMute_boundToCurrentUserOnly() {
        MuteRequest request = new MuteRequest("USER", MUTED_ID);
        MuteResponse response = Mockito.mock(MuteResponse.class);
        given(muteService.addMute("USER", MUTED_ID, USER_ID)).willReturn(response);

        ResponseEntity<ApiResponse<MuteResponse>> result = controller.addMute(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(muteService).addMute("USER", MUTED_ID, USER_ID);
    }

    @Test
    @DisplayName("removeMute は SecurityUtils.getCurrentUserId() のみを削除条件に渡す")
    void removeMute_boundToCurrentUserOnly() {
        doNothing().when(muteService).removeMute("USER", MUTED_ID, USER_ID);

        ResponseEntity<Void> result = controller.removeMute("USER", MUTED_ID);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(muteService).removeMute("USER", MUTED_ID, USER_ID);
    }

    @Test
    @DisplayName("getMutes は SecurityUtils.getCurrentUserId() のみを検索条件に渡す")
    void getMutes_boundToCurrentUserOnly() {
        given(muteService.getMutes(USER_ID)).willReturn(List.of());

        ResponseEntity<ApiResponse<List<MuteResponse>>> result = controller.getMutes();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 他人の userId を検索条件に渡す経路が存在しないことの裏取り。
        verify(muteService).getMutes(USER_ID);
    }
}
