package com.mannschaft.app.tournament.entry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.entry.dto.EntryLoadResponse;
import com.mannschaft.app.tournament.entry.dto.EntryMemberListResponse;
import com.mannschaft.app.tournament.entry.dto.EntryMemberResponse;
import com.mannschaft.app.tournament.entry.dto.LoadFromTeamRequest;
import com.mannschaft.app.tournament.entry.dto.UpsertEntryMembersRequest;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
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
 * {@link TournamentEntryMemberController} の MockMvc 結合テスト。
 *
 * <p>F08.7 Phase 9 設計書 §Phase9 に準拠:</p>
 * <ul>
 *   <li>GET エントリー一覧 → 200 OK</li>
 *   <li>POST チームメンバーから一括ロード → 200 OK</li>
 *   <li>PUT エントリー全置換 → 200 OK</li>
 *   <li>DELETE エントリー個別削除 → 204 No Content</li>
 *   <li>POST apply-template → 200 OK</li>
 *   <li>POST apply-template → 403（別チームテンプレート: TOUR_028）</li>
 *   <li>POST load-from-team → 409（エントリーロック中: TOUR_020）</li>
 * </ul>
 */
@WebMvcTest(controllers = {TournamentEntryMemberController.class, TournamentEntryTemplateController.class})
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("TournamentEntryMemberController 結合テスト (F08.7 Phase 9)")
class TournamentEntryMemberControllerIntegrationTest {

    private static final Long USER_ID = 1L;
    private static final Long ORG_ID = 10L;
    private static final Long TOURNAMENT_ID = 100L;
    private static final Long DIVISION_ID = 200L;
    private static final Long PARTICIPANT_ID = 300L;

    private static final String BASE_URL = "/api/v1/organizations/" + ORG_ID
            + "/tournaments/" + TOURNAMENT_ID
            + "/divisions/" + DIVISION_ID
            + "/participants/" + PARTICIPANT_ID
            + "/entry-members";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TournamentEntryMemberService entryMemberService;

    @MockitoBean
    private TournamentEntryTemplateService entryTemplateService;

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
    // GET エントリー一覧
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /entry-members")
    class GetEntryMembers {

        @Test
        @DisplayName("200_OK_エントリー一覧を返す")
        void GET_エントリー一覧_200_OK() throws Exception {
            EntryMemberResponse member = EntryMemberResponse.builder()
                    .id(UUID.randomUUID())
                    .participantId(PARTICIPANT_ID)
                    .userId(USER_ID)
                    .displayName("テストユーザー")
                    .sortOrder((short) 0)
                    .build();

            EntryMemberListResponse stub = EntryMemberListResponse.builder()
                    .entryMembers(List.of(member))
                    .teamMemberCandidates(null)
                    .entryCount(1)
                    .minEntryCount(null)
                    .maxEntryCount(null)
                    .build();

            given(entryMemberService.getEntryMembers(
                    eq(ORG_ID), eq(TOURNAMENT_ID), eq(DIVISION_ID), eq(PARTICIPANT_ID),
                    anyBoolean(), any()))
                    .willReturn(stub);

            mockMvc.perform(get(BASE_URL)
                            .param("includeTeamMembers", "false"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entryCount").value(1))
                    .andExpect(jsonPath("$.data.entryMembers").isArray())
                    .andExpect(jsonPath("$.data.entryMembers[0].displayName").value("テストユーザー"));
        }
    }

    // ──────────────────────────────────────────────────
    // POST チームメンバーから一括ロード
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /entry-members/load-from-team")
    class LoadFromTeam {

        @Test
        @DisplayName("200_OK_チームメンバーからロード成功")
        void POST_loadFromTeam_200_OK() throws Exception {
            EntryLoadResponse stub = EntryLoadResponse.builder()
                    .added(5)
                    .skipped(2)
                    .total(7)
                    .entryMembers(List.of())
                    .build();

            given(entryMemberService.loadFromTeamMembers(
                    eq(ORG_ID), eq(TOURNAMENT_ID), eq(DIVISION_ID), eq(PARTICIPANT_ID),
                    any(), any()))
                    .willReturn(stub);

            LoadFromTeamRequest req = LoadFromTeamRequest.builder()
                    .overwriteExisting(false)
                    .build();

            mockMvc.perform(post(BASE_URL + "/load-from-team")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.added").value(5))
                    .andExpect(jsonPath("$.data.skipped").value(2))
                    .andExpect(jsonPath("$.data.total").value(7));
        }

        @Test
        @DisplayName("409_エントリーロック中_TOUR_020")
        void POST_loadFromTeam_409_エントリーロック() throws Exception {
            willThrow(new BusinessException(TournamentErrorCode.ENTRY_LOCKED))
                    .given(entryMemberService).loadFromTeamMembers(
                            eq(ORG_ID), eq(TOURNAMENT_ID), eq(DIVISION_ID), eq(PARTICIPANT_ID),
                            any(), any());

            LoadFromTeamRequest req = LoadFromTeamRequest.builder().build();

            mockMvc.perform(post(BASE_URL + "/load-from-team")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("TOUR_020"));
        }
    }

    // ──────────────────────────────────────────────────
    // PUT エントリー全置換
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /entry-members")
    class UpsertEntryMembers {

        @Test
        @DisplayName("200_OK_エントリー全置換成功")
        void PUT_entryMembers_200_OK() throws Exception {
            EntryMemberListResponse stub = EntryMemberListResponse.builder()
                    .entryMembers(List.of())
                    .entryCount(0)
                    .minEntryCount(null)
                    .maxEntryCount(null)
                    .build();

            given(entryMemberService.upsertEntryMembers(
                    eq(ORG_ID), eq(TOURNAMENT_ID), eq(DIVISION_ID), eq(PARTICIPANT_ID),
                    any(), any()))
                    .willReturn(stub);

            UpsertEntryMembersRequest req = UpsertEntryMembersRequest.builder()
                    .members(List.of())
                    .build();

            mockMvc.perform(put(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entryCount").value(0));
        }
    }

    // ──────────────────────────────────────────────────
    // DELETE エントリー個別削除
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /entry-members/{id}")
    class DeleteEntryMember {

        @Test
        @DisplayName("204_No_Content_個別削除成功")
        void DELETE_entryMember_204() throws Exception {
            UUID entryMemberId = UUID.randomUUID();

            doNothing().when(entryMemberService).deleteEntryMember(
                    eq(ORG_ID), eq(TOURNAMENT_ID), eq(DIVISION_ID), eq(PARTICIPANT_ID),
                    eq(entryMemberId), anyBoolean(), any());

            mockMvc.perform(delete(BASE_URL + "/" + entryMemberId))
                    .andExpect(status().isNoContent());
        }
    }

    // ──────────────────────────────────────────────────
    // POST apply-template（TournamentEntryTemplateController経由）
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /entry-members/apply-template")
    class ApplyTemplate {

        @Test
        @DisplayName("200_OK_テンプレート適用成功")
        void POST_applyTemplate_200_OK() throws Exception {
            UUID templateId = UUID.randomUUID();

            com.mannschaft.app.tournament.entry.dto.ApplyTemplateResponse stub =
                    com.mannschaft.app.tournament.entry.dto.ApplyTemplateResponse.builder()
                            .applied(18)
                            .skipped(2)
                            .skippedInactive(0)
                            .total(20)
                            .entryMembers(List.of())
                            .build();

            given(entryTemplateService.applyTemplate(
                    eq(ORG_ID), eq(TOURNAMENT_ID), eq(DIVISION_ID), eq(PARTICIPANT_ID),
                    any(), any()))
                    .willReturn(stub);

            com.mannschaft.app.tournament.entry.dto.ApplyTemplateRequest req =
                    com.mannschaft.app.tournament.entry.dto.ApplyTemplateRequest.builder()
                            .templateId(templateId)
                            .overwriteExisting(false)
                            .build();

            mockMvc.perform(post(BASE_URL + "/apply-template")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.applied").value(18))
                    .andExpect(jsonPath("$.data.skipped").value(2));
        }

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

            mockMvc.perform(post(BASE_URL + "/apply-template")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("TOUR_028"));
        }
    }
}
