package com.mannschaft.app.resident.controller;

import com.mannschaft.app.resident.dto.DwellingUnitResponse;
import com.mannschaft.app.resident.dto.ResidentResponse;
import com.mannschaft.app.resident.service.ResidentRegistryService;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link UserResidentController} の単体テスト（自己スコープ契約テストを兼ねる・認可根治戦役 Wave6 ロットC）。
 *
 * <p>居住者情報は PII が濃いドメインであり、両 EP（getMyUnit/getMyResidentInfo）が
 * {@code ResidentRegistryService} へ認証主体の {@code USER_ID} のみを渡し、他居住者の情報へ
 * 到達する経路がエンドポイントに存在しないことを固定する。
 * {@code UserResidentController#getMyUnit} / {@code UserResidentController#getMyResidentInfo}
 * の自己スコープ性を固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserResidentController 単体テスト")
class UserResidentControllerTest {

    private static final Long USER_ID = 1L;

    @Mock
    private ResidentRegistryService residentService;

    @InjectMocks
    private UserResidentController controller;

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
    @DisplayName("getMyUnit: 認証主体自身の userId のみで自室情報を取得する（UserResidentController#getMyUnit）")
    void getMyUnit_自己スコープ() {
        DwellingUnitResponse res = mock(DwellingUnitResponse.class);
        given(residentService.getMyUnit(USER_ID)).willReturn(res);

        assertThat(controller.getMyUnit().getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(residentService).getMyUnit(USER_ID);
    }

    @Test
    @DisplayName("getMyResidentInfo: 認証主体自身の userId のみで居住者情報を取得する"
            + "（UserResidentController#getMyResidentInfo）")
    void getMyResidentInfo_自己スコープ() {
        ResidentResponse res = mock(ResidentResponse.class);
        given(residentService.getMyResidentInfo(USER_ID)).willReturn(res);

        assertThat(controller.getMyResidentInfo().getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(residentService).getMyResidentInfo(USER_ID);
    }
}
