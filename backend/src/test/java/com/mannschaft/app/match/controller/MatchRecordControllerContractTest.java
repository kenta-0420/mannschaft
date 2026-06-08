package com.mannschaft.app.match.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.match.MatchErrorCode;
import com.mannschaft.app.match.domain.MatchKind;
import com.mannschaft.app.match.dto.CreateMatchRequest;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
        securityUtils = mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(ACTOR);
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
}
