package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.VillageRecruitCategoryResponse;
import com.mannschaft.app.village.service.VillageRecruitCategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * F17.1 P2 — VillageRecruitCategoryController 統合テスト（MockMvc・TEST_CONVENTION §3.1.1）。
 *
 * <p>金型: {@code VillageJoinRequestControllerTest} / {@code VillageMeetupControllerTest}。
 * Controller 層の契約（URL パス・HTTP メソッド・ステータス変換・Bean Validation）を検証する。
 * 認可の 7 ケース（HEADMAN/ELDER/BAN/退村/VILLAGER/VISITOR/非村人）の分岐は Service 層の責務であり、
 * {@code VillageRecruitCategoryServiceTest} が担当する（本テストは Service をモックする）。</p>
 */
@WebMvcTest(VillageRecruitCategoryController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("F17.1 VillageRecruitCategoryController 統合テスト")
class VillageRecruitCategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VillageRecruitCategoryService service;

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
    private static final UUID CATEGORY_ID = UUID.randomUUID();
    private static final Long USER_ID = 100L;

    private VillageRecruitCategoryResponse response(String name, boolean isPreset, long recruitCount) {
        return new VillageRecruitCategoryResponse(
                CATEGORY_ID, VILLAGE_ID, name, null, null, 10, isPreset, recruitCount,
                LocalDateTime.now(), LocalDateTime.now());
    }

    @BeforeEach
    void setUpAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    // ------------------------------------------------------------------
    // GET /api/v1/villages/{id}/recruit-categories
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET recruit-categories — 正常系 200")
    void list_success() throws Exception {
        given(service.list(eq(VILLAGE_ID), eq(USER_ID)))
                .willReturn(List.of(response("参加者募集", true, 2L)));

        mockMvc.perform(get("/api/v1/villages/{villageId}/recruit-categories", VILLAGE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("参加者募集"))
                .andExpect(jsonPath("$.data[0].recruitCount").value(2));
    }

    @Test
    @DisplayName("GET recruit-categories — 非村人は VILLAGE_007（404）")
    void list_nonMember_notFound() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.NOT_MEMBER))
                .given(service).list(eq(VILLAGE_ID), eq(USER_ID));

        mockMvc.perform(get("/api/v1/villages/{villageId}/recruit-categories", VILLAGE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_007"));
    }

    // ------------------------------------------------------------------
    // POST /api/v1/villages/{id}/recruit-categories
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST recruit-categories — 正常系 201")
    void create_success() throws Exception {
        given(service.create(eq(VILLAGE_ID), eq(USER_ID), any())).willReturn(response("引っ越し手伝い", false, 0L));

        String body = """
                {
                  "name": "引っ越し手伝い"
                }
                """;

        mockMvc.perform(post("/api/v1/villages/{villageId}/recruit-categories", VILLAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("引っ越し手伝い"));
    }

    @Test
    @DisplayName("POST recruit-categories — 権限なしは VILLAGE_024（403）")
    void create_forbidden() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN))
                .given(service).create(eq(VILLAGE_ID), eq(USER_ID), any());

        String body = """
                {
                  "name": "引っ越し手伝い"
                }
                """;

        mockMvc.perform(post("/api/v1/villages/{villageId}/recruit-categories", VILLAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_024"));
    }

    @Test
    @DisplayName("POST recruit-categories — 凍結村は VILLAGE_027（409）")
    void create_archivedVillage_conflict() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.VILLAGE_ALREADY_ARCHIVED))
                .given(service).create(eq(VILLAGE_ID), eq(USER_ID), any());

        String body = """
                {
                  "name": "引っ越し手伝い"
                }
                """;

        mockMvc.perform(post("/api/v1/villages/{villageId}/recruit-categories", VILLAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_027"));
    }

    @Test
    @DisplayName("AC-16: POST recruit-categories — color が不正形式なら 400")
    void create_invalidColor_badRequest() throws Exception {
        String body = """
                {
                  "name": "引っ越し手伝い",
                  "color": "#GGGGGG"
                }
                """;

        mockMvc.perform(post("/api/v1/villages/{villageId}/recruit-categories", VILLAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-17: POST recruit-categories — name が41文字以上なら 400")
    void create_nameTooLong_badRequest() throws Exception {
        String longName = "あ".repeat(41);
        String body = "{\"name\": \"" + longName + "\"}";

        mockMvc.perform(post("/api/v1/villages/{villageId}/recruit-categories", VILLAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // PUT /api/v1/villages/{id}/recruit-categories/{categoryId}
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PUT recruit-categories/{id} — 正常系 200")
    void update_success() throws Exception {
        given(service.update(eq(VILLAGE_ID), eq(CATEGORY_ID), eq(USER_ID), any()))
                .willReturn(response("改名後", false, 1L));

        mockMvc.perform(put("/api/v1/villages/{villageId}/recruit-categories/{id}", VILLAGE_ID, CATEGORY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"改名後\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("改名後"));
    }

    @Test
    @DisplayName("AC-12: PUT recruit-categories/{id} — 他村のカテゴリは VILLAGE_083（404）")
    void update_crossVillage_notFound() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.RECRUIT_CATEGORY_NOT_FOUND))
                .given(service).update(eq(VILLAGE_ID), eq(CATEGORY_ID), eq(USER_ID), any());

        mockMvc.perform(put("/api/v1/villages/{villageId}/recruit-categories/{id}", VILLAGE_ID, CATEGORY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"改名後\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_083"));
    }

    // ------------------------------------------------------------------
    // DELETE /api/v1/villages/{id}/recruit-categories/{categoryId}
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DELETE recruit-categories/{id} — 正常系 204")
    void delete_success() throws Exception {
        mockMvc.perform(delete("/api/v1/villages/{villageId}/recruit-categories/{id}", VILLAGE_ID, CATEGORY_ID))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("AC-10: DELETE recruit-categories/{id} — 使用中は VILLAGE_086（409）")
    void delete_inUse_conflict() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.RECRUIT_CATEGORY_IN_USE))
                .given(service).delete(eq(VILLAGE_ID), eq(CATEGORY_ID), eq(USER_ID));

        mockMvc.perform(delete("/api/v1/villages/{villageId}/recruit-categories/{id}", VILLAGE_ID, CATEGORY_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_086"));
    }

    // ------------------------------------------------------------------
    // PUT /api/v1/villages/{id}/recruit-categories/order
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC-14: PUT recruit-categories/order — 正常系 200")
    void reorder_success() throws Exception {
        given(service.reorder(eq(VILLAGE_ID), eq(USER_ID), any()))
                .willReturn(List.of(response("A", false, 0L), response("B", false, 0L)));

        String body = "{\"orderedCategoryIds\": [\"" + UUID.randomUUID() + "\", \"" + UUID.randomUUID() + "\"]}";

        mockMvc.perform(put("/api/v1/villages/{villageId}/recruit-categories/order", VILLAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("A"));
    }
}
