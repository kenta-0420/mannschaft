package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.VillageCreationRequestResponse;
import com.mannschaft.app.village.entity.enums.VillageRequestStatus;
import com.mannschaft.app.village.service.VillageCreationRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * F17.1 Phase 1 B5 — VillageCreationRequestController 統合テスト。
 * 各 EP につき 2 件以上のケースを網羅する。
 */
@WebMvcTest(VillageCreationRequestController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("F17.1 VillageCreationRequestController 統合テスト")
class VillageCreationRequestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VillageCreationRequestService service;

    @MockitoBean
    private AccessControlService accessControlService;

    @MockitoBean
    private com.mannschaft.app.auth.service.AuthTokenService authTokenService;

    @MockitoBean
    private UserLocaleCache userLocaleCache;

    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID VILLAGE_ID = UUID.randomUUID();

    private VillageCreationRequestResponse pendingResponse() {
        return new VillageCreationRequestResponse(
                REQUEST_ID, 100L, "草野球村", "casual-baseball", "スポーツ",
                "草野球の交流", VillageRequestStatus.PENDING,
                null, null, null, null, LocalDateTime.now());
    }

    private VillageCreationRequestResponse approvedResponse() {
        return new VillageCreationRequestResponse(
                REQUEST_ID, 100L, "草野球村", "casual-baseball", "スポーツ",
                "草野球の交流", VillageRequestStatus.APPROVED,
                999L, LocalDateTime.now(), "問題なし", VILLAGE_ID, LocalDateTime.now());
    }

    @BeforeEach
    void setUpAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("100", null, List.of()));
    }

    // ------------------------------------------------------------------
    // POST /api/v1/villages/creation-requests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST creation-requests — 正常系 201")
    void create_success() throws Exception {
        given(service.createRequest(eq(100L), any())).willReturn(pendingResponse());

        String body = """
                {
                  "name": "草野球村",
                  "slug": "casual-baseball",
                  "category": "スポーツ",
                  "purpose": "草野球の交流",
                  "guidelineAgreedAt": "%s",
                  "joinPolicy": "FREE",
                  "visibility": "PUBLIC",
                  "type": "COMMUNITY"
                }
                """.formatted(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));

        mockMvc.perform(post("/api/v1/villages/creation-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.slug").value("casual-baseball"));
    }

    @Test
    @DisplayName("POST creation-requests — レートリミット超過で 429")
    void create_rateLimited() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.CREATION_REQUEST_THROTTLED))
                .given(service).createRequest(eq(100L), any());

        String body = """
                {
                  "name": "草野球村",
                  "slug": "casual-baseball",
                  "purpose": "p",
                  "guidelineAgreedAt": "%s",
                  "joinPolicy": "FREE",
                  "visibility": "PUBLIC",
                  "type": "COMMUNITY"
                }
                """.formatted(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));

        mockMvc.perform(post("/api/v1/villages/creation-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_010"));
    }

    // ------------------------------------------------------------------
    // GET /api/v1/me/village-creation-requests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET me/village-creation-requests — 自分の申請一覧を返す")
    void listMine_success() throws Exception {
        given(service.listMine(100L)).willReturn(List.of(pendingResponse()));

        mockMvc.perform(get("/api/v1/me/village-creation-requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].slug").value("casual-baseball"));
    }

    @Test
    @DisplayName("GET me/village-creation-requests — 空一覧でも 200")
    void listMine_empty() throws Exception {
        given(service.listMine(100L)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/me/village-creation-requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    // ------------------------------------------------------------------
    // GET /api/v1/admin/village-creation-requests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET admin/village-creation-requests — SYSTEM_ADMIN なら一覧 200")
    void listForAdmin_success() throws Exception {
        doNothing().when(accessControlService).checkSystemAdmin(anyLong());
        Page<VillageCreationRequestResponse> page = new PageImpl<>(
                List.of(pendingResponse()), PageRequest.of(0, 20), 1);
        given(service.listForAdmin(eq(VillageRequestStatus.PENDING), any())).willReturn(page);

        mockMvc.perform(get("/api/v1/admin/village-creation-requests")
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].status").value("PENDING"));
    }

    @Test
    @DisplayName("GET admin/village-creation-requests — 非運営なら 403")
    void listForAdmin_forbidden() throws Exception {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkSystemAdmin(anyLong());

        mockMvc.perform(get("/api/v1/admin/village-creation-requests"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // POST /api/v1/admin/village-creation-requests/{id}/approve
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST admin .../approve — 承認 200 + createdVillageId 返却")
    void approve_success() throws Exception {
        doNothing().when(accessControlService).checkSystemAdmin(anyLong());
        given(service.approve(eq(REQUEST_ID), eq(100L), any())).willReturn(approvedResponse());

        mockMvc.perform(post("/api/v1/admin/village-creation-requests/{id}/approve", REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewComment\":\"問題なし\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.createdVillageId").value(VILLAGE_ID.toString()));
    }

    @Test
    @DisplayName("POST admin .../approve — 既に APPROVED は 409")
    void approve_alreadyReviewed() throws Exception {
        doNothing().when(accessControlService).checkSystemAdmin(anyLong());
        willThrow(new BusinessException(VillageErrorCode.CREATION_REQUEST_ALREADY_REVIEWED))
                .given(service).approve(eq(REQUEST_ID), eq(100L), any());

        mockMvc.perform(post("/api/v1/admin/village-creation-requests/{id}/approve", REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_033"));
    }

    // ------------------------------------------------------------------
    // POST /api/v1/admin/village-creation-requests/{id}/reject
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST admin .../reject — 拒否 200")
    void reject_success() throws Exception {
        doNothing().when(accessControlService).checkSystemAdmin(anyLong());
        VillageCreationRequestResponse rejected = new VillageCreationRequestResponse(
                REQUEST_ID, 100L, "n", "s", null, "p",
                VillageRequestStatus.REJECTED, 100L, LocalDateTime.now(),
                "既存と重複", null, LocalDateTime.now());
        given(service.reject(eq(REQUEST_ID), eq(100L), any())).willReturn(rejected);

        mockMvc.perform(post("/api/v1/admin/village-creation-requests/{id}/reject", REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewComment\":\"既存と重複\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.reviewComment").value("既存と重複"));
    }

    @Test
    @DisplayName("POST admin .../reject — 申請が見つからない場合 404")
    void reject_notFound() throws Exception {
        doNothing().when(accessControlService).checkSystemAdmin(anyLong());
        willThrow(new BusinessException(VillageErrorCode.CREATION_REQUEST_NOT_FOUND))
                .given(service).reject(eq(REQUEST_ID), eq(100L), any());

        mockMvc.perform(post("/api/v1/admin/village-creation-requests/{id}/reject", REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewComment\":\"理由\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_032"));
    }

    // ------------------------------------------------------------------
    // POST /api/v1/admin/village-creation-requests/{id}/withdraw
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST admin .../withdraw — 申請者本人ならアクセス制御不要で 200")
    void withdraw_success() throws Exception {
        VillageCreationRequestResponse withdrawn = new VillageCreationRequestResponse(
                REQUEST_ID, 100L, "n", "s", null, "p",
                VillageRequestStatus.WITHDRAWN, null, LocalDateTime.now(),
                null, null, LocalDateTime.now());
        given(service.withdraw(eq(REQUEST_ID), eq(100L))).willReturn(withdrawn);

        mockMvc.perform(post("/api/v1/admin/village-creation-requests/{id}/withdraw", REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WITHDRAWN"));
    }

    @Test
    @DisplayName("POST admin .../withdraw — 第三者の取り下げ試行は 403")
    void withdraw_forbidden() throws Exception {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(service).withdraw(eq(REQUEST_ID), eq(100L));

        mockMvc.perform(post("/api/v1/admin/village-creation-requests/{id}/withdraw", REQUEST_ID))
                .andExpect(status().isForbidden());
    }
}
