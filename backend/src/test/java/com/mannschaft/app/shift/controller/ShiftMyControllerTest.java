package com.mannschaft.app.shift.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.shift.dto.MyConfirmedSlotResponse;
import com.mannschaft.app.shift.service.ShiftMyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link ShiftMyController} の MockMvc 結合テスト。
 *
 * <p>GET /api/v1/shifts/my/confirmed-slots の正常系・空リスト・未認証をテストする。</p>
 */
@WebMvcTest(ShiftMyController.class)
@AutoConfigureMockMvc
@DisplayName("ShiftMyController 結合テスト")
class ShiftMyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShiftMyService shiftMyService;

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

    // ------------------------------------------------------------------
    // GET /api/v1/shifts/my/confirmed-slots
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET confirmed-slots: 認証済みユーザーで 200 + 確定シフト一覧返却")
    @WithMockUser(username = "1")
    void getMyConfirmedSlots_authenticated_200() throws Exception {
        MyConfirmedSlotResponse slot = MyConfirmedSlotResponse.builder()
                .slotId(10L)
                .slotDate(LocalDate.of(2026, 6, 1))
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .teamId(5L)
                .teamName("テストチーム")
                .scheduleId(3L)
                .scheduleName("6月シフト")
                .positionName("レジ担当")
                .build();

        given(shiftMyService.getMyConfirmedSlots(1L)).willReturn(List.of(slot));

        mockMvc.perform(get("/api/v1/shifts/my/confirmed-slots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].slotId").value(10))
                .andExpect(jsonPath("$.data[0].slotDate").value("2026-06-01"))
                .andExpect(jsonPath("$.data[0].startTime").value("09:00:00"))
                .andExpect(jsonPath("$.data[0].endTime").value("17:00:00"))
                .andExpect(jsonPath("$.data[0].teamId").value(5))
                .andExpect(jsonPath("$.data[0].teamName").value("テストチーム"))
                .andExpect(jsonPath("$.data[0].scheduleId").value(3))
                .andExpect(jsonPath("$.data[0].scheduleName").value("6月シフト"))
                .andExpect(jsonPath("$.data[0].positionName").value("レジ担当"));
    }

    @Test
    @DisplayName("GET confirmed-slots: 確定シフトなしで空配列を返す")
    @WithMockUser(username = "2")
    void getMyConfirmedSlots_empty_200() throws Exception {
        given(shiftMyService.getMyConfirmedSlots(2L)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/shifts/my/confirmed-slots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("GET confirmed-slots: 未認証で 401 を返す")
    void getMyConfirmedSlots_unauthenticated_401() throws Exception {
        mockMvc.perform(get("/api/v1/shifts/my/confirmed-slots"))
                .andExpect(status().isUnauthorized());
    }
}
