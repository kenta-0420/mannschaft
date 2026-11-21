package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.dto.ParticipantContactSpacesResponse;
import com.mannschaft.app.tournament.service.ContactAccessLevel;
import com.mannschaft.app.tournament.service.ParticipantContactSpaceFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261118-0900 {@link ParticipantContactSpaceController} の HTTP 契約（standalone MockMvc）。
 *
 * <p>AC-P13（{@code tournamentId} の型不正は 400 で、Facade を呼ばない＝リソースの有無に依存しない）と、
 * slug を文字列のまま Facade に渡すこと（数値だけの slug を内部 ID と解釈しない。§10.3.2）、
 * Facade の拒否（TOUR_029）・読み取り失敗（X12: 500 のまま）を HTTP に写すことを固定する。</p>
 *
 * <p><b>性質</b>: Controller は骨格で実装済みのため、本クラスは red→green の red ではなく
 * 現契約を固定する characterization test（TEST_CONVENTION §3.1.1）。初回から green が正常。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ParticipantContactSpaceController — 参加者向け連絡スペース一覧の HTTP 契約")
class ParticipantContactSpaceControllerContractTest {

    private static final String URL = "/api/v1/teams/{teamSlug}/tournaments/{tournamentId}/contact-spaces";
    private static final Long USER_ID = 7L;

    @Mock
    private ParticipantContactSpaceFacade facade;

    @InjectMocks
    private ParticipantContactSpaceController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("P13: tournamentId が数値でない（abc）なら 400 で、Facade を呼ばない")
    void P13_型不正のtournamentIdは400() throws Exception {
        mockMvc.perform(get(URL, "team-a", "abc"))
                .andExpect(status().isBadRequest());

        verify(facade, never()).listForParticipant(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("P11/§10.3.2: 数値だけの slug も文字列のまま Facade に渡す（内部 ID として解釈しない）")
    void 数値だけのslugも文字列のまま渡す() throws Exception {
        given(facade.listForParticipant("12345", 99L, USER_ID)).willReturn(response());

        mockMvc.perform(get(URL, "12345", 99L))
                .andExpect(status().isOk());

        verify(facade).listForParticipant("12345", 99L, USER_ID);
    }

    @Test
    @DisplayName("P05: 200 は ApiResponse の data に包み、divisionName=null を省かずに出す")
    void 正常応答の形() throws Exception {
        given(facade.listForParticipant("team-a", 99L, USER_ID)).willReturn(response());

        mockMvc.perform(get(URL, "team-a", 99L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tournamentId").value(99))
                .andExpect(jsonPath("$.data.tournamentName").value("Cup"))
                .andExpect(jsonPath("$.data.divisions[0].divisionId").value(5))
                .andExpect(jsonPath("$.data.divisions[0].name").value("Div1"))
                .andExpect(jsonPath("$.data.spaces[0].scopeType").value("TOURNAMENT"))
                .andExpect(content().string(containsString("\"divisionName\":null")))
                .andExpect(jsonPath("$.data.spaces[0].spaceKind").value("BULLETIN"))
                .andExpect(jsonPath("$.data.spaces[0].isPublic").value(false))
                .andExpect(jsonPath("$.data.spaces[0].accessLevel").value("POST"));
    }

    @Test
    @DisplayName("P07〜P09: Facade の拒否 TOUR_029 は 404 + error.code=TOUR_029")
    void 拒否は404_TOUR_029() throws Exception {
        given(facade.listForParticipant("team-a", 99L, USER_ID))
                .willThrow(new BusinessException(TournamentErrorCode.CONTACT_SPACE_NOT_FOUND));

        mockMvc.perform(get(URL, "team-a", 99L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TOUR_029"));
    }

    @Test
    @DisplayName("X12: 参加確認後の DB 読み取り失敗は 500（200 の空・404 に変換しない）")
    void 読み取り失敗は500() throws Exception {
        given(facade.listForParticipant("team-a", 99L, USER_ID))
                .willThrow(new DataAccessResourceFailureException("db down"));

        mockMvc.perform(get(URL, "team-a", 99L))
                .andExpect(status().isInternalServerError());
    }

    private static ParticipantContactSpacesResponse response() {
        return new ParticipantContactSpacesResponse(99L, "Cup",
                List.of(new ParticipantContactSpacesResponse.DivisionItem(5L, "Div1")),
                List.of(new ParticipantContactSpacesResponse.SpaceItem(UUID.randomUUID(),
                        ContactSpaceScopeType.TOURNAMENT, 99L, null, ContactSpaceKind.BULLETIN, 1L, false,
                        ContactAccessLevel.POST)));
    }
}
