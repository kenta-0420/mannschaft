package com.mannschaft.app.pointcard.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.pointcard.dto.CreateGroupRequest;
import com.mannschaft.app.pointcard.dto.GroupDetailResponse;
import com.mannschaft.app.pointcard.dto.GroupItemResponse;
import com.mannschaft.app.pointcard.dto.GroupListItemResponse;
import com.mannschaft.app.pointcard.dto.UpdateGroupRequest;
import com.mannschaft.app.pointcard.enums.BarcodeFormat;
import com.mannschaft.app.pointcard.error.PointCardErrorCode;
import com.mannschaft.app.pointcard.service.PointCardGroupService;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link PointCardGroupController} の MockMvc 結合テスト（S3）。
 *
 * <p>カバー観点:
 * <ul>
 *   <li>各エンドポイントの HTTP ステータス + JSON 形状</li>
 *   <li>POST 作成は 201、DELETE は 204</li>
 *   <li>GROUP_LIMIT_EXCEEDED / GROUP_ITEM_LIMIT_EXCEEDED は 409 + 設計書 §6.3 のコード番号</li>
 *   <li>他人グループは 404 POINT_CARD_006（IDOR 防止）</li>
 *   <li>presentation-start は 200 で詳細を返す</li>
 * </ul>
 */
@WebMvcTest(PointCardGroupController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PointCardGroupController 結合テスト")
class PointCardGroupControllerTest {

    private static final Long USER_ID = 100L;
    private static final UUID GROUP_ID = UUID.fromString("01956c00-0000-7000-8000-000000000bbb");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PointCardGroupService groupService;

    @MockitoBean
    private AuthTokenService authTokenService;

    @MockitoBean
    private UserLocaleCache userLocaleCache;

    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @MockitoBean
    private ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    private GroupDetailResponse sampleDetail() {
        GroupItemResponse item = new GroupItemResponse(
                UUID.randomUUID(), 0,
                "東急ポイント", null, "1234567890123", BarcodeFormat.CODE128, "0123",
                UUID.randomUUID(), "tokyu_point", "東急ポイント", "#E60012", "logos/tokyu.png", true);
        return new GroupDetailResponse(
                GROUP_ID, "東急ハンズ用", "🛍️", 0,
                List.of(item),
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    // ──────────────────────────────────────────────
    // GET /api/v1/point-cards/groups
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/point-cards/groups: 200 で一覧を返す")
    void listGroups_200() throws Exception {
        GroupListItemResponse item = new GroupListItemResponse(
                GROUP_ID, "東急ハンズ用", "🛍️", 0, 3L,
                OffsetDateTime.now(), OffsetDateTime.now());
        given(groupService.listMyGroups(eq(USER_ID))).willReturn(List.of(item));

        mockMvc.perform(get("/api/v1/point-cards/groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(GROUP_ID.toString()))
                .andExpect(jsonPath("$.data[0].cardCount").value(3));
    }

    // ──────────────────────────────────────────────
    // POST /api/v1/point-cards/groups
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/point-cards/groups: 201 で詳細 DTO を返す")
    void createGroup_201() throws Exception {
        given(groupService.createGroup(eq(USER_ID), any(CreateGroupRequest.class)))
                .willReturn(sampleDetail());

        CreateGroupRequest req = new CreateGroupRequest("東急ハンズ用", "🛍️", null);
        mockMvc.perform(post("/api/v1/point-cards/groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(GROUP_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].barcodeValue").value("1234567890123"));
    }

    @Test
    @DisplayName("POST /api/v1/point-cards/groups: GROUP_LIMIT_EXCEEDED は 409 POINT_CARD_004")
    void createGroup_groupLimit_409() throws Exception {
        willThrow(new BusinessException(PointCardErrorCode.GROUP_LIMIT_EXCEEDED))
                .given(groupService).createGroup(eq(USER_ID), any(CreateGroupRequest.class));

        CreateGroupRequest req = new CreateGroupRequest("追加", null, null);
        mockMvc.perform(post("/api/v1/point-cards/groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POINT_CARD_004"));
    }

    @Test
    @DisplayName("POST /api/v1/point-cards/groups: GROUP_ITEM_LIMIT_EXCEEDED は 409 POINT_CARD_005")
    void createGroup_itemLimit_409() throws Exception {
        willThrow(new BusinessException(PointCardErrorCode.GROUP_ITEM_LIMIT_EXCEEDED))
                .given(groupService).createGroup(eq(USER_ID), any(CreateGroupRequest.class));

        CreateGroupRequest req = new CreateGroupRequest("特大", null, null);
        mockMvc.perform(post("/api/v1/point-cards/groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POINT_CARD_005"));
    }

    @Test
    @DisplayName("POST /api/v1/point-cards/groups: name 空はバリデーションで 400")
    void createGroup_blankName_400() throws Exception {
        String body = "{\"name\":\"\",\"emoji\":null}";
        mockMvc.perform(post("/api/v1/point-cards/groups")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    // ──────────────────────────────────────────────
    // GET /api/v1/point-cards/groups/{id}
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/point-cards/groups/{id}: 200 で詳細を返す")
    void getDetail_200() throws Exception {
        given(groupService.getGroupDetail(eq(GROUP_ID), eq(USER_ID))).willReturn(sampleDetail());

        mockMvc.perform(get("/api/v1/point-cards/groups/{id}", GROUP_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(GROUP_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].providerCode").value("tokyu_point"));
    }

    @Test
    @DisplayName("GET /api/v1/point-cards/groups/{id}: 他人グループは 404 POINT_CARD_006")
    void getDetail_otherUser_404() throws Exception {
        willThrow(new BusinessException(PointCardErrorCode.CARD_NOT_FOUND))
                .given(groupService).getGroupDetail(eq(GROUP_ID), eq(USER_ID));

        mockMvc.perform(get("/api/v1/point-cards/groups/{id}", GROUP_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POINT_CARD_006"));
    }

    // ──────────────────────────────────────────────
    // PATCH /api/v1/point-cards/groups/{id}
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /api/v1/point-cards/groups/{id}: 200 で更新後の詳細を返す")
    void patch_200() throws Exception {
        given(groupService.updateGroup(eq(GROUP_ID), eq(USER_ID), any(UpdateGroupRequest.class)))
                .willReturn(sampleDetail());

        UpdateGroupRequest req = new UpdateGroupRequest("リネーム", null, null, null);
        mockMvc.perform(patch("/api/v1/point-cards/groups/{id}", GROUP_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    // ──────────────────────────────────────────────
    // DELETE
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /api/v1/point-cards/groups/{id}: 204")
    void delete_204() throws Exception {
        mockMvc.perform(delete("/api/v1/point-cards/groups/{id}", GROUP_ID))
                .andExpect(status().isNoContent());
        verify(groupService).deleteGroup(eq(GROUP_ID), eq(USER_ID));
    }

    // ──────────────────────────────────────────────
    // POST /presentation-start
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/point-cards/groups/{id}/presentation-start: 200 で詳細を返す")
    void startPresentation_200() throws Exception {
        given(groupService.startPresentation(eq(GROUP_ID), eq(USER_ID)))
                .willReturn(sampleDetail());

        mockMvc.perform(post("/api/v1/point-cards/groups/{id}/presentation-start", GROUP_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].barcodeValue").value("1234567890123"));
    }

    @Test
    @DisplayName("POST /api/v1/point-cards/groups/{id}/presentation-start: 他人グループは 404 POINT_CARD_006")
    void startPresentation_otherUser_404() throws Exception {
        willThrow(new BusinessException(PointCardErrorCode.CARD_NOT_FOUND))
                .given(groupService).startPresentation(eq(GROUP_ID), eq(USER_ID));

        mockMvc.perform(post("/api/v1/point-cards/groups/{id}/presentation-start", GROUP_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POINT_CARD_006"));
    }
}
