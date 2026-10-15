package com.mannschaft.app.navsettings.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.navsettings.dto.NavFeatureAdminResponse;
import com.mannschaft.app.navsettings.service.SystemAdminNavFeaturesService;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.mannschaft.app.common.security.AccessGuard;

@WebMvcTest(SystemAdminNavFeaturesController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("SystemAdminNavFeaturesController 結合テスト")
class SystemAdminNavFeaturesControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean SystemAdminNavFeaturesService service;
    @MockitoBean AuthTokenService authTokenService;
    @MockitoBean UserLocaleCache userLocaleCache;
    @MockitoBean ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    private NavFeatureAdminResponse sampleFeature() {
        return NavFeatureAdminResponse.builder()
                .key("shift-management").labelKey("nav.shiftManagement")
                .icon("pi pi-table").path("/shift")
                .fixed(false).enabled(true).subscriptionRequired(false)
                .sortOrder(40).mobileVisible(true)
                .createdAt(Instant.parse("2026-05-28T00:00:00Z"))
                .updatedAt(Instant.parse("2026-05-28T00:00:00Z"))
                .build();
    }

    @Test
    @DisplayName("GET /system-admin/nav-features: 一覧を全件返す")
    @WithMockUser(roles = "SYSTEM_ADMIN")
    void listAll_200() throws Exception {
        given(service.listAll()).willReturn(List.of(sampleFeature()));

        mockMvc.perform(get("/api/v1/system-admin/nav-features"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].key").value("shift-management"))
                .andExpect(jsonPath("$.data[0].enabled").value(true))
                .andExpect(jsonPath("$.data[0].fixed").value(false))
                .andExpect(jsonPath("$.data[0].subscriptionRequired").value(false));
    }

    @Test
    @DisplayName("GET /system-admin/nav-features: 空一覧で200")
    @WithMockUser(roles = "SYSTEM_ADMIN")
    void listAll_empty_200() throws Exception {
        given(service.listAll()).willReturn(List.of());

        mockMvc.perform(get("/api/v1/system-admin/nav-features"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("POST /system-admin/nav-features: 正常リクエストで201+作成レスポンス")
    @WithMockUser(username = "1", roles = "SYSTEM_ADMIN")
    void create_201() throws Exception {
        given(service.create(any(), eq(1L))).willReturn(sampleFeature());

        var body = Map.of(
                "key", "shift-management",
                "labelKey", "nav.shiftManagement",
                "icon", "pi pi-table",
                "path", "/shift",
                "fixed", false,
                "enabled", true,
                "subscriptionRequired", false,
                "sortOrder", 40,
                "mobileVisible", true
        );

        mockMvc.perform(post("/api/v1/system-admin/nav-features")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.key").value("shift-management"));
    }

    @Test
    @DisplayName("PUT /system-admin/nav-features/{key}: 正常リクエストで200+更新レスポンス")
    @WithMockUser(username = "1", roles = "SYSTEM_ADMIN")
    void update_200() throws Exception {
        given(service.update(eq("shift-management"), any(), eq(1L))).willReturn(sampleFeature());

        var body = Map.of(
                "labelKey", "nav.shiftManagement",
                "icon", "pi pi-table",
                "path", "/shift",
                "fixed", false,
                "enabled", true,
                "subscriptionRequired", false,
                "sortOrder", 40,
                "mobileVisible", true
        );

        mockMvc.perform(put("/api/v1/system-admin/nav-features/shift-management")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.key").value("shift-management"));
    }

    @Test
    @DisplayName("DELETE /system-admin/nav-features/{key}: 正常リクエストで204")
    @WithMockUser(username = "1", roles = "SYSTEM_ADMIN")
    void delete_204() throws Exception {
        willDoNothing().given(service).delete(eq("shift-management"), eq(1L));

        mockMvc.perform(delete("/api/v1/system-admin/nav-features/shift-management"))
                .andExpect(status().isNoContent());
    }
}
