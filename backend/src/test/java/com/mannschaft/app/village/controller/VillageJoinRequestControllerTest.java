package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.JoinRequestResponse;
import com.mannschaft.app.village.entity.enums.VillageRequestStatus;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.service.VillageJoinRequestService;
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
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * F17.1 Phase 1 B6 — VillageJoinRequestController 統合テスト。
 *
 * <p>各 EP につき正常系・異常系を最低 2 件ずつ網羅する。</p>
 */
@WebMvcTest(VillageJoinRequestController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("F17.1 VillageJoinRequestController 統合テスト")
class VillageJoinRequestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VillageJoinRequestService service;

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

    private static final UUID VILLAGE_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID REVIEWER_MEMBERSHIP_ID = UUID.randomUUID();
    private static final Long USER_ID = 100L;

    private JoinRequestResponse pendingResponse() {
        return new JoinRequestResponse(
                REQUEST_ID, VILLAGE_ID, VillageSubjectType.USER, USER_ID,
                "よろしく", VillageRequestStatus.PENDING,
                null, null, null, LocalDateTime.now());
    }

    private JoinRequestResponse approvedResponse() {
        return new JoinRequestResponse(
                REQUEST_ID, VILLAGE_ID, VillageSubjectType.USER, USER_ID,
                "よろしく", VillageRequestStatus.APPROVED,
                REVIEWER_MEMBERSHIP_ID, LocalDateTime.now(), "歓迎", LocalDateTime.now());
    }

    @BeforeEach
    void setUpAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    // ------------------------------------------------------------------
    // POST /api/v1/villages/{id}/join-requests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST join-requests — 正常系 201")
    void create_success() throws Exception {
        given(service.createRequest(eq(VILLAGE_ID), eq(USER_ID), any())).willReturn(pendingResponse());

        String body = """
                {
                  "subjectType": "USER",
                  "subjectId": 100,
                  "message": "よろしく"
                }
                """;

        mockMvc.perform(post("/api/v1/villages/{villageId}/join-requests", VILLAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.subjectId").value(100));
    }

    @Test
    @DisplayName("POST join-requests — FREE 村への申請は 422")
    void create_freeVillage() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.VILLAGE_FREE_VILLAGE_DIRECT_JOIN))
                .given(service).createRequest(eq(VILLAGE_ID), eq(USER_ID), any());

        String body = """
                {
                  "subjectType": "USER",
                  "subjectId": 100
                }
                """;

        mockMvc.perform(post("/api/v1/villages/{villageId}/join-requests", VILLAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_041"));
    }

    @Test
    @DisplayName("POST join-requests — PENDING 重複は 409")
    void create_pendingDuplicate() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.VILLAGE_JOIN_REQUEST_PENDING_DUPLICATE))
                .given(service).createRequest(eq(VILLAGE_ID), eq(USER_ID), any());

        String body = """
                {
                  "subjectType": "USER",
                  "subjectId": 100
                }
                """;

        mockMvc.perform(post("/api/v1/villages/{villageId}/join-requests", VILLAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_039"));
    }

    // ------------------------------------------------------------------
    // GET /api/v1/villages/{id}/join-requests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET join-requests — 村長/長老なら一覧 200")
    void list_success() throws Exception {
        Page<JoinRequestResponse> page = new PageImpl<>(
                List.of(pendingResponse()), PageRequest.of(0, 20), 1);
        given(service.listForReviewers(eq(VILLAGE_ID), eq(USER_ID),
                eq(VillageRequestStatus.PENDING), anyInt(), anyInt())).willReturn(page);

        mockMvc.perform(get("/api/v1/villages/{villageId}/join-requests", VILLAGE_ID)
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].status").value("PENDING"));
    }

    @Test
    @DisplayName("GET join-requests — VILLAGER による閲覧は 403")
    void list_forbidden() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN))
                .given(service).listForReviewers(eq(VILLAGE_ID), eq(USER_ID), any(), anyInt(), anyInt());

        mockMvc.perform(get("/api/v1/villages/{villageId}/join-requests", VILLAGE_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_024"));
    }

    // ------------------------------------------------------------------
    // GET /api/v1/villages/{id}/join-requests/me
    //  申請者が「自分の」申請を取得する。審査者限定の一覧（上記）とは別 EP。
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET join-requests/me — 申請者は自分の申請を取得できる 200")
    void listMine_success() throws Exception {
        given(service.listMine(eq(VILLAGE_ID), eq(USER_ID)))
                .willReturn(List.of(pendingResponse()));

        mockMvc.perform(get("/api/v1/villages/{villageId}/join-requests/me", VILLAGE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data[0].id").value(REQUEST_ID.toString()))
                .andExpect(jsonPath("$.data[0].subjectId").value(USER_ID));
    }

    @Test
    @DisplayName("GET join-requests/me — 申請が無ければ空配列 200（404 にしない）")
    void listMine_empty() throws Exception {
        given(service.listMine(eq(VILLAGE_ID), eq(USER_ID))).willReturn(List.of());

        mockMvc.perform(get("/api/v1/villages/{villageId}/join-requests/me", VILLAGE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    /**
     * IDOR 閉塞の要（AC2）。
     *
     * <p>本 EP は「誰の申請を返すか」をパス・クエリで一切受け取らず、認証済みユーザー ID
     * （{@code SecurityUtils.getCurrentUserId()}）だけを Service へ渡す。したがって攻撃者が
     * 他人の ID を送り込む余地が構造的に無い。ここでは細工した {@code userId} /
     * {@code requesterUserId} パラメータが**無視される**ことを固定する。</p>
     */
    @Test
    @DisplayName("GET join-requests/me — 細工した userId パラメータは無視され認証ユーザーで解決される")
    void listMine_ignoresClientSuppliedUserId() throws Exception {
        Long otherUserId = 999L;
        given(service.listMine(eq(VILLAGE_ID), eq(USER_ID))).willReturn(List.of(pendingResponse()));

        mockMvc.perform(get("/api/v1/villages/{villageId}/join-requests/me", VILLAGE_ID)
                        .param("userId", String.valueOf(otherUserId))
                        .param("requesterUserId", String.valueOf(otherUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].subjectId").value(USER_ID));

        // 認証ユーザーでのみ解決され、細工された ID では絶対に呼ばれない
        verify(service).listMine(VILLAGE_ID, USER_ID);
        verify(service, never()).listMine(VILLAGE_ID, otherUserId);
    }

    @Test
    @DisplayName("GET join-requests/me — 村が存在しなければ 404")
    void listMine_villageNotFound() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND))
                .given(service).listMine(eq(VILLAGE_ID), eq(USER_ID));

        mockMvc.perform(get("/api/v1/villages/{villageId}/join-requests/me", VILLAGE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_001"));
    }

    /**
     * 本 EP が存在すること自体の番人（red 時はここが 404 で落ちた）。
     *
     * <p>Service を一切スタブしないため、実装が無ければ 404 で、在れば Mockito 既定の
     * 空リストが返って 200 になる。EP の URL・HTTP メソッドの取り違えを機械的に検知する。</p>
     */
    @Test
    @DisplayName("GET join-requests/me — 申請者向け EP が存在する（欠落なら 404）")
    void listMine_endpointExists() throws Exception {
        mockMvc.perform(get("/api/v1/villages/{villageId}/join-requests/me", VILLAGE_ID))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // POST /api/v1/villages/{id}/join-requests/{rid}/approve
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST .../approve — 正常系 200")
    void approve_success() throws Exception {
        given(service.approve(eq(VILLAGE_ID), eq(REQUEST_ID), eq(USER_ID), any()))
                .willReturn(approvedResponse());

        mockMvc.perform(post("/api/v1/villages/{villageId}/join-requests/{id}/approve",
                        VILLAGE_ID, REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewComment\":\"歓迎\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.reviewedBy").value(REVIEWER_MEMBERSHIP_ID.toString()));
    }

    @Test
    @DisplayName("POST .../approve — 既に審査済みなら 409")
    void approve_alreadyReviewed() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.VILLAGE_JOIN_REQUEST_ALREADY_REVIEWED))
                .given(service).approve(eq(VILLAGE_ID), eq(REQUEST_ID), eq(USER_ID), any());

        mockMvc.perform(post("/api/v1/villages/{villageId}/join-requests/{id}/approve",
                        VILLAGE_ID, REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_040"));
    }

    // ------------------------------------------------------------------
    // POST /api/v1/villages/{id}/join-requests/{rid}/reject
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST .../reject — 正常系 200")
    void reject_success() throws Exception {
        JoinRequestResponse rejected = new JoinRequestResponse(
                REQUEST_ID, VILLAGE_ID, VillageSubjectType.USER, USER_ID,
                "よろしく", VillageRequestStatus.REJECTED,
                REVIEWER_MEMBERSHIP_ID, LocalDateTime.now(),
                "ガイドライン違反のため", LocalDateTime.now());
        given(service.reject(eq(VILLAGE_ID), eq(REQUEST_ID), eq(USER_ID), any()))
                .willReturn(rejected);

        mockMvc.perform(post("/api/v1/villages/{villageId}/join-requests/{id}/reject",
                        VILLAGE_ID, REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewComment\":\"ガイドライン違反のため\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.reviewComment").value("ガイドライン違反のため"));
    }

    @Test
    @DisplayName("POST .../reject — 申請が存在しない場合 404")
    void reject_notFound() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.VILLAGE_JOIN_REQUEST_NOT_FOUND))
                .given(service).reject(eq(VILLAGE_ID), eq(REQUEST_ID), eq(USER_ID), any());

        mockMvc.perform(post("/api/v1/villages/{villageId}/join-requests/{id}/reject",
                        VILLAGE_ID, REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewComment\":\"理由\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_038"));
    }

    // ------------------------------------------------------------------
    // POST /api/v1/villages/{id}/join-requests/{rid}/withdraw
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST .../withdraw — 申請者本人なら 200")
    void withdraw_success() throws Exception {
        JoinRequestResponse withdrawn = new JoinRequestResponse(
                REQUEST_ID, VILLAGE_ID, VillageSubjectType.USER, USER_ID,
                null, VillageRequestStatus.WITHDRAWN,
                null, LocalDateTime.now(), null, LocalDateTime.now());
        given(service.withdraw(eq(VILLAGE_ID), eq(REQUEST_ID), eq(USER_ID))).willReturn(withdrawn);

        mockMvc.perform(post("/api/v1/villages/{villageId}/join-requests/{id}/withdraw",
                        VILLAGE_ID, REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WITHDRAWN"));
    }

    @Test
    @DisplayName("POST .../withdraw — 第三者なら 403")
    void withdraw_forbidden() throws Exception {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(service).withdraw(eq(VILLAGE_ID), eq(REQUEST_ID), eq(USER_ID));

        mockMvc.perform(post("/api/v1/villages/{villageId}/join-requests/{id}/withdraw",
                        VILLAGE_ID, REQUEST_ID))
                .andExpect(status().isForbidden());
    }
}
