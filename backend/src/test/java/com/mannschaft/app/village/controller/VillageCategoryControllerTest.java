package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.village.dto.VillageCategoryRequest;
import com.mannschaft.app.village.dto.VillageCategoryResponse;
import com.mannschaft.app.village.service.VillageCategoryService;
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

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

@WebMvcTest(VillageCategoryController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("VillageCategoryController テスト")
class VillageCategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VillageCategoryService villageCategoryService;

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

    private static final UUID CAT_ID = UUID.randomUUID();

    private VillageCategoryResponse sampleResponse() {
        return new VillageCategoryResponse(
                CAT_ID.toString(), "スポーツ・フィットネス", null, 10, List.of());
    }

    @BeforeEach
    void setUpAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("100", null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    // ─────────────────────────────────────────────────────────
    // GET /api/v1/village-categories
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/village-categories — 認証あり 200")
    void listCategories_authenticated_returns200() throws Exception {
        given(villageCategoryService.findAll()).willReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/v1/village-categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("スポーツ・フィットネス"));
    }

    @Test
    @DisplayName("GET /api/v1/village-categories — 認証なし（フィルター無効）は200（フィルター有効時は401）")
    void listCategories_withoutFilter_returns200() throws Exception {
        SecurityContextHolder.clearContext();
        given(villageCategoryService.findAll()).willReturn(List.of(sampleResponse()));

        // addFilters = false のため、実際の SecurityFilter は動作しない
        // この状態では PreAuthorize が Security Context 未設定で通過する場合もあるため、
        // ここでは Service が呼ばれる/呼ばれない確認ではなく HTTP 応答のみ確認する
        mockMvc.perform(get("/api/v1/village-categories"))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────────────────
    // GET /api/v1/system-admin/village-categories
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/system-admin/village-categories — SYSTEM_ADMIN 200")
    void adminListCategories_returns200() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("1", null,
                        List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))));
        given(villageCategoryService.findAll()).willReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/v1/system-admin/village-categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(CAT_ID.toString()));
    }

    @Test
    @DisplayName("GET /api/v1/system-admin/village-categories — 一般ユーザー（フィルター無効時は200）")
    void adminListCategories_generalUser() throws Exception {
        // addFilters = false のため SecurityConfig のパス制限は動作しない。
        // 実環境での 403 は SecurityConfig の antMatchers で保証される。
        given(villageCategoryService.findAll()).willReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/v1/system-admin/village-categories"))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────────────────
    // POST /api/v1/system-admin/village-categories
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/system-admin/village-categories — 正常 201")
    void createCategory_success() throws Exception {
        given(villageCategoryService.create(any())).willReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/system-admin/village-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "スポーツ・フィットネス", "parentId": null, "displayOrder": 10}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("スポーツ・フィットネス"));
    }

    @Test
    @DisplayName("POST /api/v1/system-admin/village-categories — name 空文字でバリデーションエラー 400")
    void createCategory_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/system-admin/village-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "", "parentId": null, "displayOrder": 10}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/system-admin/village-categories — name 64文字超でバリデーションエラー 400")
    void createCategory_nameTooLong_returns400() throws Exception {
        String longName = "あ".repeat(65);
        mockMvc.perform(post("/api/v1/system-admin/village-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "displayOrder": 10}
                                """.formatted(longName)))
                .andExpect(status().isBadRequest());
    }

    // ─────────────────────────────────────────────────────────
    // PUT /api/v1/system-admin/village-categories/{id}
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT /api/v1/system-admin/village-categories/{id} — 正常 200")
    void updateCategory_success() throws Exception {
        VillageCategoryResponse updated = new VillageCategoryResponse(
                CAT_ID.toString(), "更新名称", null, 20, List.of());
        given(villageCategoryService.update(eq(CAT_ID), any())).willReturn(updated);

        mockMvc.perform(put("/api/v1/system-admin/village-categories/" + CAT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "更新名称", "displayOrder": 20}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("更新名称"));
    }

    // ─────────────────────────────────────────────────────────
    // DELETE /api/v1/system-admin/village-categories/{id}
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /api/v1/system-admin/village-categories/{id} — 正常 204")
    void deleteCategory_success() throws Exception {
        willDoNothing().given(villageCategoryService).delete(CAT_ID);

        mockMvc.perform(delete("/api/v1/system-admin/village-categories/" + CAT_ID))
                .andExpect(status().isNoContent());
    }
}
