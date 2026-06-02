package com.mannschaft.app.securityincident;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.securityincident.controller.SystemAdminSecurityIncidentController;
import com.mannschaft.app.securityincident.dto.SecurityIncidentCreateRequest;
import com.mannschaft.app.securityincident.dto.SecurityIncidentResponse;
import com.mannschaft.app.securityincident.dto.SecurityIncidentUpdateRequest;
import com.mannschaft.app.securityincident.service.SecurityIncidentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SystemAdminSecurityIncidentController} の MockMvc テスト。
 *
 * <p>{@code @WebMvcTest} で Controller のみをロードし、Service を {@code @MockitoBean} で差し替える。</p>
 */
@DisplayName("SystemAdminSecurityIncidentController テスト")
@WebMvcTest(SystemAdminSecurityIncidentController.class)
@AutoConfigureMockMvc(addFilters = false)
class SystemAdminSecurityIncidentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SecurityIncidentService service;

    // @WebMvcTest 共通の慣習 — フィルター・コンテキスト依存
    @MockitoBean
    private AuthTokenService authTokenService;
    @MockitoBean
    private UserLocaleCache userLocaleCache;
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;

    private static final UUID INCIDENT_ID = UUID.randomUUID();
    private static final LocalDateTime DETECTED_AT = LocalDateTime.of(2026, 6, 1, 10, 0);

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("1", null,
                        List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))));
    }

    private SecurityIncidentResponse sampleResponse() {
        return SecurityIncidentResponse.builder()
                .id(INCIDENT_ID)
                .incidentType(SecurityIncidentType.DATA_BREACH)
                .severity(SecurityIncidentSeverity.HIGH)
                .detectedAt(DETECTED_AT)
                .status(SecurityIncidentStatus.OPEN)
                .createdAt(DETECTED_AT)
                .updatedAt(DETECTED_AT)
                .minutesUntil70hAlert(100L)
                .build();
    }

    @Test
    @DisplayName("POST / — 正常系: 201 Created")
    void create_returns201() throws Exception {
        SecurityIncidentCreateRequest req = SecurityIncidentCreateRequest.builder()
                .incidentType(SecurityIncidentType.DATA_BREACH)
                .severity(SecurityIncidentSeverity.HIGH)
                .detectedAt(DETECTED_AT)
                .description("個人データが外部に漏洩した可能性がある")
                .build();

        given(service.create(any())).willReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/system-admin/security-incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(INCIDENT_ID.toString()))
                .andExpect(jsonPath("$.data.incidentType").value("DATA_BREACH"))
                .andExpect(jsonPath("$.data.severity").value("HIGH"))
                .andExpect(jsonPath("$.data.status").value("OPEN"));
    }

    @Test
    @DisplayName("POST / — バリデーション: incidentType が null で 400")
    void create_missingIncidentType_returns400() throws Exception {
        String reqJson = "{\"severity\":\"HIGH\",\"detectedAt\":\"2026-06-01T10:00:00\"}";

        mockMvc.perform(post("/api/v1/system-admin/security-incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reqJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET / — 正常系: 200 OK（リスト）")
    void findAll_returns200() throws Exception {
        given(service.findAll()).willReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/v1/system-admin/security-incidents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(INCIDENT_ID.toString()))
                .andExpect(jsonPath("$.data[0].incidentType").value("DATA_BREACH"));
    }

    @Test
    @DisplayName("GET / — 正常系: 200 OK（空リスト）")
    void findAll_empty_returns200() throws Exception {
        given(service.findAll()).willReturn(List.of());

        mockMvc.perform(get("/api/v1/system-admin/security-incidents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("PATCH /{id} — 正常系: 200 OK（ステータス更新）")
    void update_returns200() throws Exception {
        SecurityIncidentUpdateRequest req = SecurityIncidentUpdateRequest.builder()
                .status(SecurityIncidentStatus.INVESTIGATING)
                .build();

        SecurityIncidentResponse updated = SecurityIncidentResponse.builder()
                .id(INCIDENT_ID)
                .incidentType(SecurityIncidentType.DATA_BREACH)
                .severity(SecurityIncidentSeverity.HIGH)
                .detectedAt(DETECTED_AT)
                .status(SecurityIncidentStatus.INVESTIGATING)
                .createdAt(DETECTED_AT)
                .updatedAt(DETECTED_AT)
                .minutesUntil70hAlert(100L)
                .build();

        given(service.update(eq(INCIDENT_ID), any())).willReturn(updated);

        mockMvc.perform(patch("/api/v1/system-admin/security-incidents/" + INCIDENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INVESTIGATING"));
    }

    @Test
    @DisplayName("PATCH /{id} — DPA 通知記録: 200 OK（notifiedDpaAt セット）")
    void update_markDpaNotified_returns200() throws Exception {
        SecurityIncidentUpdateRequest req = SecurityIncidentUpdateRequest.builder()
                .markDpaNotified(true)
                .build();

        LocalDateTime notifiedAt = LocalDateTime.of(2026, 6, 2, 9, 0);
        SecurityIncidentResponse updated = SecurityIncidentResponse.builder()
                .id(INCIDENT_ID)
                .incidentType(SecurityIncidentType.DATA_BREACH)
                .severity(SecurityIncidentSeverity.HIGH)
                .detectedAt(DETECTED_AT)
                .status(SecurityIncidentStatus.OPEN)
                .notifiedDpaAt(notifiedAt)
                .createdAt(DETECTED_AT)
                .updatedAt(DETECTED_AT)
                .minutesUntil70hAlert(100L)
                .build();

        given(service.update(eq(INCIDENT_ID), any())).willReturn(updated);

        mockMvc.perform(patch("/api/v1/system-admin/security-incidents/" + INCIDENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notifiedDpaAt").exists());
    }

    @Test
    @DisplayName("PATCH /{id} — 存在しない ID: 404")
    void update_notFound_returns404() throws Exception {
        UUID unknownId = UUID.randomUUID();
        SecurityIncidentUpdateRequest req = SecurityIncidentUpdateRequest.builder()
                .status(SecurityIncidentStatus.CLOSED)
                .build();

        given(service.update(eq(unknownId), any()))
                .willThrow(new BusinessException(SecurityIncidentErrorCode.SECURITY_INCIDENT_NOT_FOUND));

        mockMvc.perform(patch("/api/v1/system-admin/security-incidents/" + unknownId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }
}
