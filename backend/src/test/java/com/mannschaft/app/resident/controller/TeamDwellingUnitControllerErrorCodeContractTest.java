package com.mannschaft.app.resident.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.resident.ResidentErrorCode;
import com.mannschaft.app.resident.dto.DwellingUnitResponse;
import com.mannschaft.app.resident.service.DwellingUnitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可監査 Wave6 ロットF — {@code RESIDENT_001}（{@link ResidentErrorCode#DWELLING_UNIT_NOT_FOUND}）が
 * {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} 登録どおり 404 で返ることを実 HTTP 経路で固定する
 * 契約テスト。
 *
 * <p>単体アサーション（{@code resolveHttpStatus} の直接呼び出し）だけでは「エンドポイント越しに
 * 実際に 404 が返る」ことの証明にならないため、{@code addFilters=false} の {@code @WebMvcTest} で
 * コントローラー→{@code GlobalExceptionHandler} の実経路を通す。陽性対照（正当な取得は 200 で
 * 成功する）も併記し、写像追加が正常系を巻き添えにしていないことを固定する。</p>
 */
@WebMvcTest(TeamDwellingUnitController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("TeamDwellingUnitController エラーコード契約テスト（ロットF: RESIDENT_001 → 404）")
class TeamDwellingUnitControllerErrorCodeContractTest {

    private static final Long USER_ID = 100L;
    private static final Long TEAM_ID = 10L;
    private static final Long UNIT_ID = 999L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DwellingUnitService dwellingUnitService;

    @MockitoBean
    private AuthTokenService authTokenService;

    @MockitoBean
    private UserLocaleCache userLocaleCache;

    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @MockitoBean
    private ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @Test
    @DisplayName("陰性: 居室が存在しない場合は 404 RESIDENT_001 が返る")
    void get_居室不在は404() throws Exception {
        willThrow(new BusinessException(ResidentErrorCode.DWELLING_UNIT_NOT_FOUND))
                .given(dwellingUnitService).getByTeam(anyLong(), anyLong(), anyLong());

        mockMvc.perform(get("/api/v1/teams/{teamId}/dwelling-units/{id}", TEAM_ID, UNIT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESIDENT_001"));
    }

    @Test
    @DisplayName("陽性対照: 居室が存在すれば 200 で正常応答する（写像追加が正常系を壊していないこと）")
    void get_居室存在は200() throws Exception {
        DwellingUnitResponse response = mock(DwellingUnitResponse.class);
        given(dwellingUnitService.getByTeam(anyLong(), anyLong(), anyLong())).willReturn(response);

        mockMvc.perform(get("/api/v1/teams/{teamId}/dwelling-units/{id}", TEAM_ID, UNIT_ID))
                .andExpect(status().isOk());
    }
}
