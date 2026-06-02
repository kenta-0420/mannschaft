package com.mannschaft.app.tournament.entry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.entry.dto.CreateEntryTemplateRequest;
import com.mannschaft.app.tournament.entry.dto.EntryTemplateDetailResponse;
import com.mannschaft.app.tournament.entry.dto.EntryTemplateResponse;
import com.mannschaft.app.tournament.entry.dto.UpdateEntryTemplateRequest;
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

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link TournamentEntryTemplateController} の MockMvc 結合テスト。
 *
 * <p>F08.7 Phase 9-B 設計書 §Phase9-B に準拠:</p>
 * <ul>
 *   <li>GET テンプレート一覧 → 200 OK</li>
 *   <li>POST テンプレート作成 → 201 Created</li>
 *   <li>GET テンプレート詳細 → 200 OK</li>
 *   <li>PUT テンプレート更新 → 200 OK</li>
 *   <li>DELETE テンプレート論理削除 → 204 No Content</li>
 *   <li>POST テンプレート作成（5件超）→ 422 Unprocessable Entity（TOUR_025）</li>
 *   <li>POST apply-template → 403（別チームテンプレート: TOUR_028）</li>
 * </ul>
 */
@WebMvcTest(controllers = {TournamentEntryTemplateController.class, TournamentEntryMemberController.class})
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("TournamentEntryTemplateController 結合テスト (F08.7 Phase 9-B)")
class TournamentEntryTemplateControllerIntegrationTest {

    private static final Long USER_ID = 1L;
    private static final Long ORG_ID = 10L;
    private static final Long TEAM_ID = 400L;
    private static final Long TOURNAMENT_ID = 100L;
    private static final Long DIVISION_ID = 200L;
    private static final Long PARTICIPANT_ID = 300L;

    private static final String TEMPLATE_BASE_URL = "/api/v1/organizations/" + ORG_ID
            + "/teams/" + TEAM_ID + "/entry-templates";

    private static final String APPLY_URL = "/api/v1/organizations/" + ORG_ID
            + "/tournaments/" + TOURNAMENT_ID
            + "/divisions/" + DIVISION_ID
            + "/participants/" + PARTICIPANT_ID
            + "/entry-members/apply-template";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TournamentEntryTemplateService entryTemplateService;

    @MockitoBean
    private TournamentEntryMemberService entryMemberService;

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
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ──────────────────────────────────────────────────
    // GET テンプレート一覧
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /entry-templates")
    class GetTemplates {

        @Test
        @DisplayName("200_OK_テンプレート一覧を返す")
        void GET_templates_200_OK() throws Exception {
            UUID templateId = UUID.randomUUID();
            EntryTemplateResponse template = EntryTemplateResponse.builder()
                    .id(templateId)
                    .name("定番20名リスト")
                    .description("通常試合用")
                    .sortOrder((short) 0)
                    .memberCount(20)
                    .build();

            given(entryTemplateService.getTemplates(eq(ORG_ID), eq(TEAM_ID), any()))
                    .willReturn(List.of(template));

            mockMvc.perform(get(TEMPLATE_BASE_URL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data[0].name").value("定番20名リスト"))
                    .andExpect(jsonPath("$.data[0].memberCount").value(20));
        }
    }

    // ──────────────────────────────────────────────────
    // POST テンプレート作成
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /entry-templates")
    class CreateTemplate {

        @Test
        @DisplayName("201_Created_テンプレート作成成功")
        void POST_template_201_Created() throws Exception {
            UUID templateId = UUID.randomUUID();
            EntryTemplateDetailResponse stub = EntryTemplateDetailResponse.builder()
                    .id(templateId)
                    .name("新テンプレート")
                    .sortOrder((short) 0)
                    .members(List.of())
                    .build();

            given(entryTemplateService.createTemplate(eq(ORG_ID), eq(TEAM_ID), any(), any()))
                    .willReturn(stub);

            CreateEntryTemplateRequest req = CreateEntryTemplateRequest.builder()
                    .name("新テンプレート")
                    .members(List.of())
                    .build();

            mockMvc.perform(post(TEMPLATE_BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.name").value("新テンプレート"));
        }

        @Test
        @DisplayName("422_テンプレート5件超_TOUR_025")
        void POST_template_422_5件超() throws Exception {
            willThrow(new BusinessException(TournamentErrorCode.MAX_TEMPLATE_COUNT_EXCEEDED))
                    .given(entryTemplateService).createTemplate(eq(ORG_ID), eq(TEAM_ID), any(), any());

            CreateEntryTemplateRequest req = CreateEntryTemplateRequest.builder()
                    .name("6件目テンプレート")
                    .members(List.of())
                    .build();

            mockMvc.perform(post(TEMPLATE_BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("TOUR_025"));
        }
    }

    // ──────────────────────────────────────────────────
    // GET テンプレート詳細
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /entry-templates/{id}")
    class GetTemplate {

        @Test
        @DisplayName("200_OK_テンプレート詳細を返す")
        void GET_template_200_OK() throws Exception {
            UUID templateId = UUID.randomUUID();
            EntryTemplateDetailResponse stub = EntryTemplateDetailResponse.builder()
                    .id(templateId)
                    .name("詳細テンプレート")
                    .sortOrder((short) 0)
                    .members(List.of())
                    .build();

            given(entryTemplateService.getTemplate(eq(ORG_ID), eq(TEAM_ID), eq(templateId), any()))
                    .willReturn(stub);

            mockMvc.perform(get(TEMPLATE_BASE_URL + "/" + templateId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("詳細テンプレート"));
        }
    }

    // ──────────────────────────────────────────────────
    // PUT テンプレート更新
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /entry-templates/{id}")
    class UpdateTemplate {

        @Test
        @DisplayName("200_OK_テンプレート更新成功")
        void PUT_template_200_OK() throws Exception {
            UUID templateId = UUID.randomUUID();
            EntryTemplateDetailResponse stub = EntryTemplateDetailResponse.builder()
                    .id(templateId)
                    .name("更新後テンプレート")
                    .sortOrder((short) 0)
                    .members(List.of())
                    .build();

            given(entryTemplateService.updateTemplate(eq(ORG_ID), eq(TEAM_ID), eq(templateId), any(), any()))
                    .willReturn(stub);

            UpdateEntryTemplateRequest req = UpdateEntryTemplateRequest.builder()
                    .name("更新後テンプレート")
                    .members(List.of())
                    .build();

            mockMvc.perform(put(TEMPLATE_BASE_URL + "/" + templateId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("更新後テンプレート"));
        }
    }

    // ──────────────────────────────────────────────────
    // DELETE テンプレート論理削除
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /entry-templates/{id}")
    class DeleteTemplate {

        @Test
        @DisplayName("204_No_Content_論理削除成功")
        void DELETE_template_204() throws Exception {
            UUID templateId = UUID.randomUUID();

            doNothing().when(entryTemplateService).deleteTemplate(
                    eq(ORG_ID), eq(TEAM_ID), eq(templateId), any());

            mockMvc.perform(delete(TEMPLATE_BASE_URL + "/" + templateId))
                    .andExpect(status().isNoContent());
        }
    }

    // ──────────────────────────────────────────────────
    // POST apply-template
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("POST apply-template")
    class ApplyTemplate {

        @Test
        @DisplayName("403_別チームのテンプレート_TOUR_028")
        void POST_applyTemplate_403_別チームテンプレート() throws Exception {
            UUID templateId = UUID.randomUUID();

            willThrow(new BusinessException(TournamentErrorCode.TEMPLATE_TEAM_MISMATCH))
                    .given(entryTemplateService).applyTemplate(
                            eq(ORG_ID), eq(TOURNAMENT_ID), eq(DIVISION_ID), eq(PARTICIPANT_ID),
                            any(), any());

            com.mannschaft.app.tournament.entry.dto.ApplyTemplateRequest req =
                    com.mannschaft.app.tournament.entry.dto.ApplyTemplateRequest.builder()
                            .templateId(templateId)
                            .build();

            mockMvc.perform(post(APPLY_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("TOUR_028"));
        }
    }
}
