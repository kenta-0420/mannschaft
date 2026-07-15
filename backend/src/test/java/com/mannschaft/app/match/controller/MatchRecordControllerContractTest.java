package com.mannschaft.app.match.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.config.OrgScopeIdConverter;
import com.mannschaft.app.config.TeamScopeIdConverter;
import com.mannschaft.app.match.MatchErrorCode;
import com.mannschaft.app.match.domain.HomeAway;
import com.mannschaft.app.match.domain.MatchKind;
import com.mannschaft.app.match.domain.MatchStatus;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.dto.CreateMatchRequest;
import com.mannschaft.app.match.dto.MatchSummaryResponse;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.service.MatchAccessService;
import com.mannschaft.app.match.service.MatchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.format.support.FormattingConversionService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MatchRecordController} の HTTP 契約テスト（02 §F・03 §C.2 / §C.4a）。
 *
 * <p>検証: teamId/createdBy/organizationId が <b>DTO ではなくパス＋principal からサーバー導出</b>される
 * （マスアサインメント防止・03 §C.4a）、取得の閲覧不可は 404（IDOR 秘匿）、作成の必須欠落 400、happy path。
 * {@code standaloneSetup} で method security を回避する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchRecordController HTTP 契約テスト")
class MatchRecordControllerContractTest {

    @Mock
    private MatchService matchService;
    @Mock
    private MatchAccessService matchAccessService;

    @InjectMocks
    private MatchRecordController controller;

    private MockMvc mockMvc;
    private MockedStatic<SecurityUtils> securityUtils;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final long ACTOR = 1L;
    private static final long ORG = 100L;
    private static final long TEAM = 200L;
    private static final UUID MATCH_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setConversionService(scopeConversionService())
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
        securityUtils = mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(ACTOR);
    }

    /**
     * 型付きパス変数 {@code OrgScopeId}/{@code TeamScopeId}（課題 #12・案A）の変換器を登録した
     * 変換サービス。本テストは数値 ID のみを渡すため slug 解決 Service は呼ばれない（高速パス）。
     */
    private FormattingConversionService scopeConversionService() {
        FormattingConversionService cs = new DefaultFormattingConversionService();
        cs.addConverter(new OrgScopeIdConverter(org.mockito.Mockito.mock(
                com.mannschaft.app.organization.service.OrganizationService.class)));
        cs.addConverter(new TeamScopeIdConverter(org.mockito.Mockito.mock(
                com.mannschaft.app.team.service.TeamService.class)));
        return cs;
    }

    @AfterEach
    void tearDown() {
        securityUtils.close();
    }

    private CreateMatchRequest validCreate() {
        CreateMatchRequest req = new CreateMatchRequest();
        req.setKind(MatchKind.PRACTICE);
        req.setOpponentName("対戦相手A");
        return req;
    }

    private MatchEntity savedMatch() {
        return MatchEntity.builder()
                .organizationId(ORG).teamId(TEAM).kind(MatchKind.PRACTICE).createdBy(ACTOR)
                .build();
    }

    @Test
    @DisplayName("作成: teamId/createdBy/organizationId はパス＋principal からサーバー導出される")
    void create_derivesServerSideOwnership() throws Exception {
        given(matchService.create(any(), eq(ACTOR))).willReturn(savedMatch());

        mockMvc.perform(post("/api/v1/organizations/{orgId}/teams/{teamId}/matches", ORG, TEAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreate())))
                .andExpect(status().isCreated());

        ArgumentCaptor<MatchService.CreateCommand> captor =
                ArgumentCaptor.forClass(MatchService.CreateCommand.class);
        verify(matchService).create(captor.capture(), eq(ACTOR));
        MatchService.CreateCommand cmd = captor.getValue();
        assertThat(cmd.getOrganizationId()).isEqualTo(ORG);
        assertThat(cmd.getTeamId()).isEqualTo(TEAM);
        assertThat(cmd.getCreatedBy()).isEqualTo(ACTOR);
    }

    @Test
    @DisplayName("作成: kind 欠落（@NotNull 違反）は 400")
    void create_missingKind_400() throws Exception {
        CreateMatchRequest req = validCreate();
        req.setKind(null);

        mockMvc.perform(post("/api/v1/organizations/{orgId}/teams/{teamId}/matches", ORG, TEAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("取得: 閲覧不可（F00）なら 404（MATCH_001・存在を漏らさない）")
    void getMatch_notViewable_404() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessException(MatchErrorCode.MATCH_001))
                .when(matchAccessService).assertCanView(eq(ACTOR), eq(MATCH_ID));

        mockMvc.perform(get("/api/v1/organizations/{orgId}/teams/{teamId}/matches/{matchId}",
                        ORG, TEAM, MATCH_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("取得: 閲覧可なら 200")
    void getMatch_viewable_200() throws Exception {
        given(matchService.getMatchOrThrow(MATCH_ID, ORG)).willReturn(savedMatch());

        mockMvc.perform(get("/api/v1/organizations/{orgId}/teams/{teamId}/matches/{matchId}",
                        ORG, TEAM, MATCH_ID))
                .andExpect(status().isOk());
    }

    // ─── 予定からの解決（入口④・二重起票防止） ──────────────────

    @Test
    @DisplayName("by-schedule: 既存試合があれば 200・data にサマリを返す")
    void resolveBySchedule_existing_200() throws Exception {
        long scheduleId = 555L;
        given(matchService.resolveByScheduleId(eq(ORG), eq(TEAM), eq(ACTOR), eq(scheduleId)))
                .willReturn(java.util.Optional.of(summaryRow()));

        mockMvc.perform(get("/api/v1/organizations/{orgId}/teams/{teamId}/matches/by-schedule/{scheduleId}",
                        ORG, TEAM, scheduleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(MATCH_ID.toString()));
    }

    @Test
    @DisplayName("by-schedule: 既存が無ければ 200・data:null（FE は作成へ分岐）")
    void resolveBySchedule_none_200NullData() throws Exception {
        long scheduleId = 556L;
        given(matchService.resolveByScheduleId(eq(ORG), eq(TEAM), eq(ACTOR), eq(scheduleId)))
                .willReturn(java.util.Optional.empty());

        mockMvc.perform(get("/api/v1/organizations/{orgId}/teams/{teamId}/matches/by-schedule/{scheduleId}",
                        ORG, TEAM, scheduleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("by-schedule: 非メンバー（Service 第一防御 403）は 403 を返す")
    void resolveBySchedule_nonMember_403() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessException(MatchErrorCode.MATCH_010))
                .when(matchService).resolveByScheduleId(eq(ORG), eq(TEAM), eq(ACTOR), eq(557L));

        mockMvc.perform(get("/api/v1/organizations/{orgId}/teams/{teamId}/matches/by-schedule/{scheduleId}",
                        ORG, TEAM, 557L))
                .andExpect(status().isForbidden());
    }

    // ─── 大会の対戦カードからの解決（入口①・二重起票防止） ──────────────

    @Test
    @DisplayName("by-fixture: 既存試合があれば 200・data にサマリを返す")
    void resolveByFixture_existing_200() throws Exception {
        long fixtureId = 8801L;
        given(matchService.resolveByFixtureId(eq(ORG), eq(TEAM), eq(ACTOR), eq(fixtureId)))
                .willReturn(java.util.Optional.of(summaryRow()));

        mockMvc.perform(get("/api/v1/organizations/{orgId}/teams/{teamId}/matches/by-fixture/{fixtureId}",
                        ORG, TEAM, fixtureId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(MATCH_ID.toString()));
    }

    @Test
    @DisplayName("by-fixture: 既存が無ければ 200・data:null（FE は作成へ分岐）")
    void resolveByFixture_none_200NullData() throws Exception {
        long fixtureId = 8802L;
        given(matchService.resolveByFixtureId(eq(ORG), eq(TEAM), eq(ACTOR), eq(fixtureId)))
                .willReturn(java.util.Optional.empty());

        mockMvc.perform(get("/api/v1/organizations/{orgId}/teams/{teamId}/matches/by-fixture/{fixtureId}",
                        ORG, TEAM, fixtureId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("by-fixture: 非メンバー（Service 第一防御 403）は 403 を返す")
    void resolveByFixture_nonMember_403() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessException(MatchErrorCode.MATCH_010))
                .when(matchService).resolveByFixtureId(eq(ORG), eq(TEAM), eq(ACTOR), eq(8803L));

        mockMvc.perform(get("/api/v1/organizations/{orgId}/teams/{teamId}/matches/by-fixture/{fixtureId}",
                        ORG, TEAM, 8803L))
                .andExpect(status().isForbidden());
    }

    // ─── 一覧（コレクション GET・Phase2C） ────────────────────────

    private MatchSummaryResponse summaryRow() {
        return MatchSummaryResponse.builder()
                .id(MATCH_ID)
                .sport(Sport.SOCCER)
                .kind(MatchKind.PRACTICE)
                .homeAway(HomeAway.HOME)
                .opponentName("対戦相手A")
                .status(MatchStatus.SCHEDULED)
                .build();
    }

    @Test
    @DisplayName("一覧: happy path は 200・data 配列＋ページ meta を返す")
    void listMatches_happyPath_200() throws Exception {
        given(matchService.listMatches(eq(ORG), eq(TEAM), eq(ACTOR), any(), any()))
                .willReturn(new PageImpl<>(List.of(summaryRow()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/organizations/{orgId}/teams/{teamId}/matches", ORG, TEAM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(MATCH_ID.toString()))
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(20));
    }

    @Test
    @DisplayName("一覧: kind/status/期間フィルタ＋ページングが ListFilter / Pageable に正しく渡る")
    void listMatches_passesFiltersAndPaging() throws Exception {
        given(matchService.listMatches(eq(ORG), eq(TEAM), eq(ACTOR), any(), any()))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(2, 5), 0));

        mockMvc.perform(get("/api/v1/organizations/{orgId}/teams/{teamId}/matches", ORG, TEAM)
                        .param("kind", "TOURNAMENT")
                        .param("status", "COMPLETED")
                        .param("sport", "SOCCER")
                        .param("from", "2026-01-01T00:00:00")
                        .param("to", "2026-12-31T23:59:59")
                        .param("page", "2")
                        .param("size", "5"))
                .andExpect(status().isOk());

        ArgumentCaptor<MatchService.ListFilter> filterCaptor =
                ArgumentCaptor.forClass(MatchService.ListFilter.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(matchService).listMatches(eq(ORG), eq(TEAM), eq(ACTOR),
                filterCaptor.capture(), pageableCaptor.capture());

        MatchService.ListFilter filter = filterCaptor.getValue();
        assertThat(filter.getKind()).isEqualTo(MatchKind.TOURNAMENT);
        assertThat(filter.getStatus()).isEqualTo(MatchStatus.COMPLETED);
        assertThat(filter.getSport()).isEqualTo(Sport.SOCCER);
        assertThat(filter.getFrom()).isEqualTo(LocalDateTime.parse("2026-01-01T00:00:00"));
        assertThat(filter.getTo()).isEqualTo(LocalDateTime.parse("2026-12-31T23:59:59"));

        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(5);
    }

    @Test
    @DisplayName("一覧: フィルタ未指定なら ListFilter は全 null（＝絞り込まない）")
    void listMatches_noFilters_allNull() throws Exception {
        given(matchService.listMatches(eq(ORG), eq(TEAM), eq(ACTOR), any(), any()))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/organizations/{orgId}/teams/{teamId}/matches", ORG, TEAM))
                .andExpect(status().isOk());

        ArgumentCaptor<MatchService.ListFilter> filterCaptor =
                ArgumentCaptor.forClass(MatchService.ListFilter.class);
        verify(matchService).listMatches(eq(ORG), eq(TEAM), eq(ACTOR), filterCaptor.capture(), any());
        MatchService.ListFilter filter = filterCaptor.getValue();
        assertThat(filter.getKind()).isNull();
        assertThat(filter.getStatus()).isNull();
        assertThat(filter.getSport()).isNull();
        assertThat(filter.getFrom()).isNull();
        assertThat(filter.getTo()).isNull();
    }

    @Test
    @DisplayName("一覧: 非メンバー（Service 第一防御 403）は 403 を返す")
    void listMatches_nonMember_403() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessException(MatchErrorCode.MATCH_010))
                .when(matchService).listMatches(eq(ORG), eq(TEAM), eq(ACTOR), any(), any());

        mockMvc.perform(get("/api/v1/organizations/{orgId}/teams/{teamId}/matches", ORG, TEAM))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("一覧: 所有/権限列（createdBy・scorekeeperUserId・canEditMeta 等）を露出しない")
    void listMatches_doesNotExposeOwnershipColumns() throws Exception {
        given(matchService.listMatches(eq(ORG), eq(TEAM), eq(ACTOR), any(), any()))
                .willReturn(new PageImpl<>(List.of(summaryRow()), PageRequest.of(0, 20), 1));

        String body = mockMvc.perform(get("/api/v1/organizations/{orgId}/teams/{teamId}/matches", ORG, TEAM))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("createdBy");
        assertThat(body).doesNotContain("scorekeeperUserId");
        assertThat(body).doesNotContain("canEditMeta");
        assertThat(body).doesNotContain("canRecordTimeline");
        assertThat(body).doesNotContain("owningTeamId");
        assertThat(body).doesNotContain("notes");
    }
}
