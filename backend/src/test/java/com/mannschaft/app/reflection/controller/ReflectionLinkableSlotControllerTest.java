package com.mannschaft.app.reflection.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.reflection.dto.LinkableSlotResponse;
import com.mannschaft.app.reflection.service.ReflectionLinkableSlotService;
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

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ReflectionLinkableSlotController} API 契約テスト（F06.5 Phase 2・§11.3 EP #16）。
 *
 * <p>カバー AC-30（200 + dedup 済みリスト）/ AC-33（未認証 → 401）/ 空配列 200 ケース。</p>
 */
@WebMvcTest(ReflectionLinkableSlotController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ReflectionLinkableSlotController 契約テスト")
class ReflectionLinkableSlotControllerTest {

    private static final Long USER_ID = 100L;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ReflectionLinkableSlotService reflectionLinkableSlotService;
    @MockitoBean private AuthTokenService authTokenService;
    @MockitoBean private UserLocaleCache userLocaleCache;
    @MockitoBean private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean private ProxyInputContext proxyInputContext;
    @MockitoBean private AccessGuard accessGuard;

    @BeforeEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @Test
    @DisplayName("AC-33: 未認証で GET /linkable-slots すると 401")
    void listLinkableSlots_unauthenticated_401() throws Exception {
        // 認証なし
        mockMvc.perform(get("/api/v1/me/reflections/linkable-slots"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC-30: 認証済みで dedup 済みリストが 200 で返る（subjectName・courseCode・kind を検証）")
    void listLinkableSlots_authenticated_200_withList() throws Exception {
        authenticate();
        List<LinkableSlotResponse> candidates = List.of(
                new LinkableSlotResponse("PERSONAL", 10L, "数学I", "MA101", "田中先生", "1限"),
                new LinkableSlotResponse("PERSONAL", 20L, "英語", null, null, "2限")
        );
        given(reflectionLinkableSlotService.listLinkableSlots(eq(USER_ID), any(LocalDate.class)))
                .willReturn(candidates);

        mockMvc.perform(get("/api/v1/me/reflections/linkable-slots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].kind").value("PERSONAL"))
                .andExpect(jsonPath("$.data[0].subjectName").value("数学I"))
                .andExpect(jsonPath("$.data[0].courseCode").value("MA101"))
                .andExpect(jsonPath("$.data[1].subjectName").value("英語"));
    }

    @Test
    @DisplayName("AC-30: 時間割未登録（空配列）でも 200 で空配列が返る")
    void listLinkableSlots_noTimetable_200_emptyArray() throws Exception {
        authenticate();
        given(reflectionLinkableSlotService.listLinkableSlots(eq(USER_ID), any(LocalDate.class)))
                .willReturn(List.of());

        mockMvc.perform(get("/api/v1/me/reflections/linkable-slots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
