package com.mannschaft.app.navsettings.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.navsettings.dto.NavFeatureResponse;
import com.mannschaft.app.navsettings.dto.NavSettingsResponse;
import com.mannschaft.app.navsettings.service.NavSettingsService;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.mannschaft.app.common.security.AccessGuard;

@WebMvcTest(NavSettingsController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("NavSettingsController 結合テスト")
class NavSettingsControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean NavSettingsService navSettingsService;
    @MockitoBean AuthTokenService authTokenService;
    @MockitoBean UserLocaleCache userLocaleCache;
    @MockitoBean ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    @Test
    @DisplayName("GET /settings/nav: 認証済みで200+ナビ設定返却")
    @WithMockUser(username = "1")
    void getNavSettings_authenticated_200() throws Exception {
        NavFeatureResponse feature = NavFeatureResponse.builder()
                .key("calendar").labelKey("nav.calendar").icon("pi pi-calendar")
                .path("/calendar").fixed(true).sortOrder(20).mobileVisible(true).visible(true)
                .build();
        given(navSettingsService.getMyNavSettings(1L))
                .willReturn(NavSettingsResponse.builder().features(List.of(feature)).build());

        mockMvc.perform(get("/api/v1/settings/nav"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.features").isArray())
                .andExpect(jsonPath("$.data.features[0].key").value("calendar"))
                .andExpect(jsonPath("$.data.features[0].fixed").value(true))
                .andExpect(jsonPath("$.data.features[0].visible").value(true));
    }

    @Test
    @DisplayName("GET /settings/nav: レスポンスが空配列の場合も200")
    @WithMockUser(username = "1")
    void getNavSettings_empty_200() throws Exception {
        given(navSettingsService.getMyNavSettings(1L))
                .willReturn(NavSettingsResponse.builder().features(List.of()).build());

        mockMvc.perform(get("/api/v1/settings/nav"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.features").isArray())
                .andExpect(jsonPath("$.data.features").isEmpty());
    }

    @Test
    @DisplayName("PUT /settings/nav: 認証済み・正常リクエストで204")
    @WithMockUser(username = "1")
    void updateNavSettings_authenticated_204() throws Exception {
        willDoNothing().given(navSettingsService).updateMyNavSettings(eq(1L), any(), any());

        mockMvc.perform(put("/api/v1/settings/nav")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("hiddenNavKeys", List.of("todo", "my-shift")))))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PUT /settings/nav: navDisplayOrder 付きリクエストで204・順序がサービスに渡る")
    @WithMockUser(username = "1")
    void updateNavSettings_withDisplayOrder_204() throws Exception {
        willDoNothing().given(navSettingsService).updateMyNavSettings(eq(1L), any(), any());

        mockMvc.perform(put("/api/v1/settings/nav")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "hiddenNavKeys", List.of("todo"),
                                "navDisplayOrder", List.of("calendar", "chat", "todo")))))
                .andExpect(status().isNoContent());

        org.mockito.ArgumentCaptor<List<String>> orderCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.BDDMockito.then(navSettingsService).should()
                .updateMyNavSettings(eq(1L), any(), orderCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(orderCaptor.getValue())
                .containsExactly("calendar", "chat", "todo");
    }

    @Test
    @DisplayName("PUT /settings/nav: 空配列で204")
    @WithMockUser(username = "1")
    void updateNavSettings_emptyKeys_204() throws Exception {
        willDoNothing().given(navSettingsService).updateMyNavSettings(eq(1L), any(), any());

        mockMvc.perform(put("/api/v1/settings/nav")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("hiddenNavKeys", List.of()))))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PUT /settings/nav: hiddenNavKeys が null で400")
    @WithMockUser(username = "1")
    void updateNavSettings_nullKeys_400() throws Exception {
        mockMvc.perform(put("/api/v1/settings/nav")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hiddenNavKeys\": null}"))
                .andExpect(status().isBadRequest());
    }
}
