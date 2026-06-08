package com.mannschaft.app.match.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.match.MatchErrorCode;
import com.mannschaft.app.match.domain.MatchEventType;
import com.mannschaft.app.match.domain.PeriodType;
import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.dto.MatchEventRequest;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.entity.MatchEventEntity;
import com.mannschaft.app.match.service.MatchAccessService;
import com.mannschaft.app.match.service.MatchEventService;
import com.mannschaft.app.match.service.MatchService;
import com.mannschaft.app.match.service.MatchStatsAggregationService;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MatchRecordEventController} の HTTP 契約テスト（02 §F.4・03 §C.4 / §C.4a）。
 *
 * <p>検証: recorded_by_team_id が <b>DTO に無くサーバー導出される</b>こと（マスアサインメント防止・03 §C.4a）、
 * 記録権限のあるチームを導出できない場合 403、入力検証 400、happy path。
 * {@code standaloneSetup} で method security を回避する（@accessGuard SpEL の罠回避）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchRecordEventController HTTP 契約テスト")
class MatchRecordEventControllerContractTest {

    @Mock
    private MatchService matchService;
    @Mock
    private MatchEventService matchEventService;
    @Mock
    private MatchAccessService matchAccessService;
    @Mock
    private MatchStatsAggregationService aggregationService;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private MatchRecordEventController controller;

    private MockMvc mockMvc;
    private MockedStatic<SecurityUtils> securityUtils;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final long ACTOR = 1L;
    private static final long ORG = 100L;
    private static final long HOME_TEAM = 200L;
    private static final long AWAY_TEAM = 300L;
    private static final UUID MATCH_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
        securityUtils = mockStaticSecurity();
    }

    private MockedStatic<SecurityUtils> mockStaticSecurity() {
        MockedStatic<SecurityUtils> m = org.mockito.Mockito.mockStatic(SecurityUtils.class);
        m.when(SecurityUtils::getCurrentUserId).thenReturn(ACTOR);
        return m;
    }

    @AfterEach
    void tearDown() {
        securityUtils.close();
    }

    private MatchEntity coopMatch() {
        // 共同記録（has_scorekeeper=false）・HOME=200 / AWAY=300
        return MatchEntity.builder()
                .organizationId(ORG)
                .teamId(HOME_TEAM)
                .opponentTeamId(AWAY_TEAM)
                .hasScorekeeper(false)
                .build();
    }

    private MatchEventRequest validHomeGoal() {
        MatchEventRequest req = new MatchEventRequest();
        req.setPeriod(PeriodType.FIRST_HALF);
        req.setEventType(MatchEventType.GOAL);
        req.setTeamSide(TeamSide.HOME);
        req.setMinute(10);
        req.setSortSeq(0);
        return req;
    }

    @Test
    @DisplayName("記録: recorded_by_team_id は DTO ではなく principal の所属チームからサーバー導出される")
    void record_derivesRecordedByTeamId_fromPrincipal() throws Exception {
        given(matchService.getMatchOrThrow(MATCH_ID, ORG)).willReturn(coopMatch());
        // principal は HOME チーム(200)の ADMIN
        given(accessControlService.isAdminOrAbove(ACTOR, HOME_TEAM, "TEAM")).willReturn(true);
        given(matchEventService.record(eq(MATCH_ID), eq(ORG), eq(ACTOR), any()))
                .willReturn(MatchEventEntity.builder().matchId(MATCH_ID).build());

        mockMvc.perform(post("/api/v1/organizations/{orgId}/matches/{matchId}/events", ORG, MATCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validHomeGoal())))
                .andExpect(status().isCreated());

        ArgumentCaptor<MatchEventService.EventCommand> captor =
                ArgumentCaptor.forClass(MatchEventService.EventCommand.class);
        verify(matchEventService).record(eq(MATCH_ID), eq(ORG), eq(ACTOR), captor.capture());
        // DTO 由来ではなく、principal が ADMIN の HOME_TEAM がサーバー導出された
        assertThat(captor.getValue().getRecordedByTeamId()).isEqualTo(HOME_TEAM);
    }

    @Test
    @DisplayName("記録: principal がいずれのチームの ADMIN でもない（非記録係）なら 403（名義導出不可）")
    void record_noAdminTeam_403() throws Exception {
        given(matchService.getMatchOrThrow(MATCH_ID, ORG)).willReturn(coopMatch());
        given(accessControlService.isAdminOrAbove(ACTOR, HOME_TEAM, "TEAM")).willReturn(false);
        given(accessControlService.isAdminOrAbove(ACTOR, AWAY_TEAM, "TEAM")).willReturn(false);

        mockMvc.perform(post("/api/v1/organizations/{orgId}/matches/{matchId}/events", ORG, MATCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validHomeGoal())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("記録: assertCanRecordTimeline が 403 を投げれば 403（Service 二重防御）")
    void record_cannotRecord_403() throws Exception {
        given(matchService.getMatchOrThrow(MATCH_ID, ORG)).willReturn(coopMatch());
        doThrow(new BusinessException(MatchErrorCode.MATCH_010))
                .when(matchAccessService).assertCanRecordTimeline(eq(ACTOR), any());

        mockMvc.perform(post("/api/v1/organizations/{orgId}/matches/{matchId}/events", ORG, MATCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validHomeGoal())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("記録: 親 match がテナント越境/不在なら 404（MATCH_001・IDOR 秘匿）")
    void record_matchNotFound_404() throws Exception {
        given(matchService.getMatchOrThrow(MATCH_ID, ORG))
                .willThrow(new BusinessException(MatchErrorCode.MATCH_001));

        mockMvc.perform(post("/api/v1/organizations/{orgId}/matches/{matchId}/events", ORG, MATCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validHomeGoal())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("記録: period 欠落（@NotNull 違反）は 400")
    void record_missingPeriod_400() throws Exception {
        MatchEventRequest req = validHomeGoal();
        req.setPeriod(null);

        mockMvc.perform(post("/api/v1/organizations/{orgId}/matches/{matchId}/events", ORG, MATCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("記録: minute 範囲外（151>150）は 400")
    void record_minuteOutOfRange_400() throws Exception {
        MatchEventRequest req = validHomeGoal();
        req.setMinute(151);

        mockMvc.perform(post("/api/v1/organizations/{orgId}/matches/{matchId}/events", ORG, MATCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }
}
