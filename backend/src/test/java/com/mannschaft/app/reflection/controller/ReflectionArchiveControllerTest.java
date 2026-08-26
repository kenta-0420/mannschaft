package com.mannschaft.app.reflection.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.reflection.ReflectionErrorCode;
import com.mannschaft.app.reflection.dto.ArchiveFolderResponse;
import com.mannschaft.app.reflection.dto.BulkArchiveRequest;
import com.mannschaft.app.reflection.dto.BulkArchiveResult;
import com.mannschaft.app.reflection.dto.ReflectionThemeResponse;
import com.mannschaft.app.reflection.service.ReflectionArchiveService;
import com.mannschaft.app.reflection.service.ReflectionThemeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ReflectionArchiveController} API 契約テスト（F06.5 Phase 3・EP #17/#18/#21・AC-42/AC-43）。
 *
 * <p>カバー: folders 200/401 / search 200/401/400(size超過) / bulk-archive 200/401/400(条件なし)。</p>
 */
@WebMvcTest(ReflectionArchiveController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ReflectionArchiveController 契約テスト")
class ReflectionArchiveControllerTest {

    private static final Long USER_ID = 100L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private ReflectionArchiveService archiveService;
    @MockitoBean private AuthTokenService authTokenService;
    @MockitoBean private UserLocaleCache userLocaleCache;
    @MockitoBean private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean private ProxyInputContext proxyInputContext;
    @MockitoBean private AccessGuard accessGuard;

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @BeforeEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ─── EP #17: folders ────────────────────────────────────────────

    @Test
    @DisplayName("AC-42: GET /archive/folders が認証済みで 200 + フォルダ一覧を返す")
    void getFolders_authenticated_200() throws Exception {
        authenticate();
        List<ArchiveFolderResponse> folders = List.of(
                ArchiveFolderResponse.builder().academicYear(2026).termLabel("1学期")
                        .subjectName("数学").themeCount(2).build()
        );
        given(archiveService.getFolders(USER_ID)).willReturn(folders);

        mockMvc.perform(get("/api/v1/me/reflections/archive/folders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].academicYear").value(2026))
                .andExpect(jsonPath("$.data[0].termLabel").value("1学期"))
                .andExpect(jsonPath("$.data[0].themeCount").value(2));
    }

    @Test
    @DisplayName("AC-42: GET /archive/folders が未認証で 401")
    void getFolders_unauthenticated_401() throws Exception {
        mockMvc.perform(get("/api/v1/me/reflections/archive/folders"))
                .andExpect(status().isUnauthorized());
    }

    // ─── EP #18: search ────────────────────────────────────────────

    @Test
    @DisplayName("AC-43: GET /archive/search が認証済みで 200 + ページング結果を返す")
    void search_authenticated_200() throws Exception {
        authenticate();
        ReflectionThemeResponse themeResponse = ReflectionThemeResponse.builder()
                .id(UUID.randomUUID().toString()).userId(USER_ID).title("数学テーマ").build();
        given(archiveService.search(eq(USER_ID), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .willReturn(new PageImpl<>(List.of(themeResponse)));

        mockMvc.perform(get("/api/v1/me/reflections/archive/search")
                        .param("academicYear", "2026")
                        .param("termLabel", "1学期"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].title").value("数学テーマ"));
    }

    @Test
    @DisplayName("AC-43: GET /archive/search が未認証で 401")
    void search_unauthenticated_401() throws Exception {
        mockMvc.perform(get("/api/v1/me/reflections/archive/search"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC-43: GET /archive/search で size=51（上限超過）は 400")
    void search_sizeLimitExceeded_400() throws Exception {
        authenticate();
        given(archiveService.search(eq(USER_ID), any(), any(), any(), any(), any(), anyInt(), eq(51)))
                .willThrow(new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID));

        mockMvc.perform(get("/api/v1/me/reflections/archive/search")
                        .param("size", "51"))
                .andExpect(status().isBadRequest());
    }

    // ─── EP #21: bulk-archive ───────────────────────────────────────

    @Test
    @DisplayName("POST /archive/bulk-archive が認証済みで 200 + archivedCount を返す")
    void bulkArchive_authenticated_200() throws Exception {
        authenticate();
        BulkArchiveRequest request = new BulkArchiveRequest(2025, "1学期", null);
        given(archiveService.bulkArchive(eq(USER_ID), any()))
                .willReturn(BulkArchiveResult.builder().archivedCount(3).build());

        mockMvc.perform(post("/api/v1/me/reflections/archive/bulk-archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.archivedCount").value(3));
    }

    @Test
    @DisplayName("POST /archive/bulk-archive が未認証で 401")
    void bulkArchive_unauthenticated_401() throws Exception {
        mockMvc.perform(post("/api/v1/me/reflections/archive/bulk-archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BulkArchiveRequest(2025, null, null))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /archive/bulk-archive で条件なし（全null）は 400（BULK_ARCHIVE_NO_CONDITION）")
    void bulkArchive_noCondition_400() throws Exception {
        authenticate();
        given(archiveService.bulkArchive(eq(USER_ID), any()))
                .willThrow(new BusinessException(ReflectionErrorCode.REFLECTION_BULK_ARCHIVE_NO_CONDITION));

        mockMvc.perform(post("/api/v1/me/reflections/archive/bulk-archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BulkArchiveRequest(null, null, null))))
                .andExpect(status().isBadRequest());
    }
}
