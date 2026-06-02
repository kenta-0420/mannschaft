package com.mannschaft.app.favorite.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.favorite.FavoriteEntityType;
import com.mannschaft.app.favorite.FavoriteErrorCode;
import com.mannschaft.app.favorite.dto.FavoriteCheckResultDto;
import com.mannschaft.app.favorite.dto.FavoriteItemDto;
import com.mannschaft.app.favorite.service.FavoriteService;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link FavoriteController} MockMvc 単体テスト。
 *
 * <p>各エンドポイントの正常系・異常系を検証する。
 * フィルタは {@code addFilters=false} で無効化し、認証は SecurityContext に直接セットする。</p>
 */
@WebMvcTest(FavoriteController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("FavoriteController 単体テスト")
class FavoriteControllerTest {

    private static final Long USER_ID = 1L;
    private static final UUID FAVORITE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private FavoriteService favoriteService;

    // JwtAuthenticationFilter 依存解決用
    @MockitoBean
    private AuthTokenService authTokenService;

    // UserLocaleFilter 依存解決用
    @MockitoBean
    private UserLocaleCache userLocaleCache;

    // ProxyInputContextFilter の依存解決用（@WebMvcTest コンテキストで必要）
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @MockitoBean
    private ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void setUp() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ─────────────────────────────────────────────────────────────────
    // ヘルパーメソッド
    // ─────────────────────────────────────────────────────────────────

    private FavoriteItemDto createItemDto(UUID id, FavoriteEntityType type, String entityId) {
        return new FavoriteItemDto(
                id, type, entityId, 0,
                "表示名", "/icon.png", "/teams/1", true, true,
                LocalDateTime.of(2026, 5, 15, 10, 0));
    }

    // ─────────────────────────────────────────────────────────────────
    // GET /api/v1/me/favorites
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/me/favorites")
    class ListFavorites {

        @Test
        @DisplayName("正常系: 空リスト → 200 で空配列が返る")
        void listFavorites_空リスト_200で空配列() throws Exception {
            given(favoriteService.getFavorites(USER_ID)).willReturn(List.of());

            mockMvc.perform(get("/api/v1/me/favorites"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("正常系: お気に入りあり → 200 で entityType が返る")
        void listFavorites_お気に入りあり_200でデータが返る() throws Exception {
            FavoriteItemDto dto = createItemDto(FAVORITE_ID, FavoriteEntityType.TEAM, "1");
            given(favoriteService.getFavorites(USER_ID)).willReturn(List.of(dto));

            mockMvc.perform(get("/api/v1/me/favorites"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].entityType").value("TEAM"))
                    .andExpect(jsonPath("$.data[0].entityId").value("1"))
                    .andExpect(jsonPath("$.data[0].available").value(true));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // POST /api/v1/me/favorites
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/me/favorites")
    class AddFavorite {

        @Test
        @DisplayName("正常系: TEAM追加 → 201でFavoriteResponseが返る")
        void addFavorite_正常_201() throws Exception {
            FavoriteItemDto dto = createItemDto(FAVORITE_ID, FavoriteEntityType.TEAM, "1");
            given(favoriteService.addFavorite(eq(USER_ID), eq(FavoriteEntityType.TEAM), eq("1")))
                    .willReturn(dto);

            String body = objectMapper.writeValueAsString(
                    Map.of("entityType", "TEAM", "entityId", "1"));

            mockMvc.perform(post("/api/v1/me/favorites")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.entityType").value("TEAM"))
                    .andExpect(jsonPath("$.data.displayName").value("表示名"))
                    .andExpect(jsonPath("$.data.available").value(true));
        }

        @Test
        @DisplayName("異常系: entityTypeが空 → 400 バリデーションエラー COMMON_001")
        void addFavorite_entityType空_400バリデーションエラー() throws Exception {
            String body = objectMapper.writeValueAsString(
                    Map.of("entityType", "", "entityId", "1"));

            mockMvc.perform(post("/api/v1/me/favorites")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        }

        @Test
        @DisplayName("異常系: entityType=INVALID → 400 FAV_005")
        void addFavorite_entityTypeINVALID_400FAV005() throws Exception {
            String body = objectMapper.writeValueAsString(
                    Map.of("entityType", "INVALID", "entityId", "1"));

            mockMvc.perform(post("/api/v1/me/favorites")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("FAV_005"));
        }

        @Test
        @DisplayName("異常系: FAV_003（エンティティ不存在）→ 404")
        void addFavorite_FAV003_404() throws Exception {
            given(favoriteService.addFavorite(eq(USER_ID), eq(FavoriteEntityType.TEAM), eq("999")))
                    .willThrow(new BusinessException(FavoriteErrorCode.FAV_003));

            String body = objectMapper.writeValueAsString(
                    Map.of("entityType", "TEAM", "entityId", "999"));

            mockMvc.perform(post("/api/v1/me/favorites")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("FAV_003"));
        }

        @Test
        @DisplayName("異常系: FAV_002（上限超過）→ 422")
        void addFavorite_FAV002_422() throws Exception {
            given(favoriteService.addFavorite(eq(USER_ID), eq(FavoriteEntityType.TEAM), eq("1")))
                    .willThrow(new BusinessException(FavoriteErrorCode.FAV_002));

            String body = objectMapper.writeValueAsString(
                    Map.of("entityType", "TEAM", "entityId", "1"));

            mockMvc.perform(post("/api/v1/me/favorites")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("FAV_002"));
        }

        @Test
        @DisplayName("異常系: FAV_001（重複登録）→ 409")
        void addFavorite_FAV001_409() throws Exception {
            given(favoriteService.addFavorite(eq(USER_ID), eq(FavoriteEntityType.TEAM), eq("1")))
                    .willThrow(new BusinessException(FavoriteErrorCode.FAV_001));

            String body = objectMapper.writeValueAsString(
                    Map.of("entityType", "TEAM", "entityId", "1"));

            mockMvc.perform(post("/api/v1/me/favorites")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("FAV_001"));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // GET /api/v1/me/favorites/{favoriteId}
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/me/favorites/{favoriteId}")
    class GetFavorite {

        @Test
        @DisplayName("正常系: 取得成功 → 200")
        void getFavorite_取得成功_200() throws Exception {
            FavoriteItemDto dto = createItemDto(FAVORITE_ID, FavoriteEntityType.TEAM, "1");
            given(favoriteService.getFavoriteById(eq(USER_ID), eq(FAVORITE_ID)))
                    .willReturn(dto);

            mockMvc.perform(get("/api/v1/me/favorites/{favoriteId}", FAVORITE_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(FAVORITE_ID.toString()))
                    .andExpect(jsonPath("$.data.entityType").value("TEAM"));
        }

        @Test
        @DisplayName("異常系: FAV_003 → 404")
        void getFavorite_FAV003_404() throws Exception {
            given(favoriteService.getFavoriteById(eq(USER_ID), eq(FAVORITE_ID)))
                    .willThrow(new BusinessException(FavoriteErrorCode.FAV_003));

            mockMvc.perform(get("/api/v1/me/favorites/{favoriteId}", FAVORITE_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("FAV_003"));
        }

        @Test
        @DisplayName("異常系: FAV_004（IDOR）→ 403")
        void getFavorite_FAV004_403() throws Exception {
            given(favoriteService.getFavoriteById(eq(USER_ID), eq(FAVORITE_ID)))
                    .willThrow(new BusinessException(FavoriteErrorCode.FAV_004));

            mockMvc.perform(get("/api/v1/me/favorites/{favoriteId}", FAVORITE_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FAV_004"));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // DELETE /api/v1/me/favorites/{favoriteId}
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/v1/me/favorites/{favoriteId}")
    class RemoveFavorite {

        @Test
        @DisplayName("正常系: 削除成功 → 204 本文なし")
        void removeFavorite_削除成功_204() throws Exception {
            doNothing().when(favoriteService).removeFavorite(eq(USER_ID), eq(FAVORITE_ID));

            mockMvc.perform(delete("/api/v1/me/favorites/{favoriteId}", FAVORITE_ID))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("異常系: FAV_004（IDOR）→ 403")
        void removeFavorite_FAV004_403() throws Exception {
            willThrow(new BusinessException(FavoriteErrorCode.FAV_004))
                    .given(favoriteService).removeFavorite(eq(USER_ID), eq(FAVORITE_ID));

            mockMvc.perform(delete("/api/v1/me/favorites/{favoriteId}", FAVORITE_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FAV_004"));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // PATCH /api/v1/me/favorites/reorder
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PATCH /api/v1/me/favorites/reorder")
    class ReorderFavorites {

        @Test
        @DisplayName("正常系: 並び替え成功 → 204")
        void reorderFavorites_正常_204() throws Exception {
            doNothing().when(favoriteService).reorderFavorites(eq(USER_ID), any());

            String body = objectMapper.writeValueAsString(
                    Map.of("orderedIds", List.of(FAVORITE_ID.toString())));

            mockMvc.perform(patch("/api/v1/me/favorites/reorder")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("異常系: orderedIdsが空 → 400 バリデーションエラー COMMON_001")
        void reorderFavorites_orderedIds空_400バリデーションエラー() throws Exception {
            String body = objectMapper.writeValueAsString(
                    Map.of("orderedIds", List.of()));

            mockMvc.perform(patch("/api/v1/me/favorites/reorder")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // GET /api/v1/me/favorites/check
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/me/favorites/check")
    class CheckFavorite {

        @Test
        @DisplayName("正常系: 未登録 → 200 isFavorited=false favoriteId=null")
        void checkFavorite_未登録_200() throws Exception {
            given(favoriteService.checkFavorite(eq(USER_ID), eq(FavoriteEntityType.TEAM), eq("999")))
                    .willReturn(new FavoriteCheckResultDto(false, null));

            mockMvc.perform(get("/api/v1/me/favorites/check")
                            .param("entityType", "TEAM")
                            .param("entityId", "999"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isFavorited").value(false))
                    .andExpect(jsonPath("$.data.favoriteId").doesNotExist());
        }

        @Test
        @DisplayName("正常系: 登録済み → 200 isFavorited=true と favoriteId 文字列")
        void checkFavorite_登録済み_200() throws Exception {
            given(favoriteService.checkFavorite(eq(USER_ID), eq(FavoriteEntityType.TEAM), eq("1")))
                    .willReturn(new FavoriteCheckResultDto(true, FAVORITE_ID));

            mockMvc.perform(get("/api/v1/me/favorites/check")
                            .param("entityType", "TEAM")
                            .param("entityId", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isFavorited").value(true))
                    .andExpect(jsonPath("$.data.favoriteId").value(FAVORITE_ID.toString()));
        }

        @Test
        @DisplayName("異常系: entityType=INVALID → 400 FAV_005")
        void checkFavorite_entityType不正_400FAV005() throws Exception {
            mockMvc.perform(get("/api/v1/me/favorites/check")
                            .param("entityType", "INVALID")
                            .param("entityId", "1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("FAV_005"));
        }
    }
}
