package com.mannschaft.app.tournament.scorekeeper;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.repository.TournamentMatchRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * F08.7 順位UI 項目③ — {@link TournamentMatchAccessService#canEnterScore} の 3-way 認可番人テスト。
 *
 * <p>確定方針: スコア入力可＝「ORG 管理者 OR 当該大会の指名スコアキーパー OR その試合の参加チーム ADMIN」。
 * 純 Mockito で各立場の許可/拒否を実アサートする。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TournamentMatchAccessService — スコア入力 3-way 認可番人（項目③）")
class TournamentMatchAccessServiceTest {

    private static final Long ORG_ID = 100L;
    private static final Long T_ID = 7L;
    private static final Long MATCH_ID = 21L;
    private static final Long HOME_PARTICIPANT_ID = 31L;
    private static final Long AWAY_PARTICIPANT_ID = 32L;
    private static final Long HOME_TEAM_ID = 41L;
    private static final Long AWAY_TEAM_ID = 42L;

    private static final Long ORG_ADMIN = 1L;
    private static final Long SCOREKEEPER = 2L;
    private static final Long HOME_TEAM_ADMIN = 3L;
    private static final Long UNRELATED_TEAM_ADMIN = 4L;
    private static final Long PLAIN_USER = 5L;

    @Mock
    private AccessControlService accessControlService;
    @Mock
    private TournamentRepository tournamentRepository;
    @Mock
    private TournamentScorekeeperRepository scorekeeperRepository;
    @Mock
    private TournamentMatchRepository matchRepository;
    @Mock
    private TournamentParticipantRepository participantRepository;

    @InjectMocks
    private TournamentMatchAccessService service;

    @BeforeEach
    void setUp() {
        TournamentEntity tournament = TournamentEntity.builder()
                .organizationId(ORG_ID)
                .name("T")
                .createdBy(ORG_ADMIN)
                .build();
        when(tournamentRepository.findById(T_ID)).thenReturn(Optional.of(tournament));

        // match → participants
        TournamentMatchEntity match = TournamentMatchEntity.builder()
                .matchdayId(50L)
                .homeParticipantId(HOME_PARTICIPANT_ID)
                .awayParticipantId(AWAY_PARTICIPANT_ID)
                .build();
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(match));
        when(matchRepository.countByIdAndTournamentId(MATCH_ID, T_ID)).thenReturn(1L);

        when(participantRepository.findById(HOME_PARTICIPANT_ID)).thenReturn(Optional.of(
                TournamentParticipantEntity.builder().divisionId(60L).teamId(HOME_TEAM_ID).build()));
        when(participantRepository.findById(AWAY_PARTICIPANT_ID)).thenReturn(Optional.of(
                TournamentParticipantEntity.builder().divisionId(60L).teamId(AWAY_TEAM_ID).build()));

        // 既定はすべて否定（各テストで個別に許可する）
        lenient().when(accessControlService.isSystemAdmin(org.mockito.ArgumentMatchers.anyLong())).thenReturn(false);
        lenient().when(accessControlService.isAdminOrAbove(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        lenient().when(scorekeeperRepository.existsByTournamentIdAndUserId(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(false);
    }

    @Nested
    @DisplayName("canEnterScore（特定試合・3-way）")
    class CanEnterScore {

        @Test
        @DisplayName("条件①: ORG 管理者は可")
        void orgAdmin_allowed() {
            when(accessControlService.isAdminOrAbove(ORG_ADMIN, ORG_ID, "ORGANIZATION")).thenReturn(true);
            assertThat(service.canEnterScore(ORG_ADMIN, ORG_ID, T_ID, MATCH_ID)).isTrue();
        }

        @Test
        @DisplayName("SYSTEM_ADMIN は無条件で可")
        void systemAdmin_allowed() {
            when(accessControlService.isSystemAdmin(ORG_ADMIN)).thenReturn(true);
            assertThat(service.canEnterScore(ORG_ADMIN, ORG_ID, T_ID, MATCH_ID)).isTrue();
        }

        @Test
        @DisplayName("条件②: 指名スコアキーパーは可")
        void scorekeeper_allowed() {
            when(scorekeeperRepository.existsByTournamentIdAndUserId(T_ID, SCOREKEEPER)).thenReturn(true);
            assertThat(service.canEnterScore(SCOREKEEPER, ORG_ID, T_ID, MATCH_ID)).isTrue();
        }

        @Test
        @DisplayName("条件③: 参加チーム（home）ADMIN は自チーム関与試合で可")
        void participatingTeamAdmin_allowed() {
            when(accessControlService.isAdminOrAbove(HOME_TEAM_ADMIN, HOME_TEAM_ID, "TEAM")).thenReturn(true);
            assertThat(service.canEnterScore(HOME_TEAM_ADMIN, ORG_ID, T_ID, MATCH_ID)).isTrue();
        }

        @Test
        @DisplayName("無関係チームの ADMIN は不可")
        void unrelatedTeamAdmin_denied() {
            when(accessControlService.isAdminOrAbove(UNRELATED_TEAM_ADMIN, 999L, "TEAM")).thenReturn(true);
            assertThat(service.canEnterScore(UNRELATED_TEAM_ADMIN, ORG_ID, T_ID, MATCH_ID)).isFalse();
        }

        @Test
        @DisplayName("一般ユーザーは不可")
        void plainUser_denied() {
            assertThat(service.canEnterScore(PLAIN_USER, ORG_ID, T_ID, MATCH_ID)).isFalse();
        }

        @Test
        @DisplayName("未認証（userId=null）は不可")
        void anonymous_denied() {
            assertThat(service.canEnterScore((Long) null, ORG_ID, T_ID, MATCH_ID)).isFalse();
        }

        @Test
        @DisplayName("大会が指定組織に属さない場合は不可（IDOR）")
        void tournamentNotInOrg_denied() {
            // ORG_ADMIN は本当の主催組織 ADMIN だが、別組織 ID で照会されたら拒否
            when(accessControlService.isAdminOrAbove(ORG_ADMIN, ORG_ID, "ORGANIZATION")).thenReturn(true);
            assertThat(service.canEnterScore(ORG_ADMIN, 999L, T_ID, MATCH_ID)).isFalse();
        }

        @Test
        @DisplayName("matchId が当該大会に属さない場合、参加チーム ADMIN でも不可（IDOR）")
        void matchNotInTournament_denied() {
            when(matchRepository.countByIdAndTournamentId(MATCH_ID, T_ID)).thenReturn(0L);
            when(accessControlService.isAdminOrAbove(HOME_TEAM_ADMIN, HOME_TEAM_ID, "TEAM")).thenReturn(true);
            assertThat(service.canEnterScore(HOME_TEAM_ADMIN, ORG_ID, T_ID, MATCH_ID)).isFalse();
        }
    }

    @Nested
    @DisplayName("canEnterScoreTournamentWide（batch/import・条件①②のみ）")
    class CanEnterScoreTournamentWide {

        @Test
        @DisplayName("ORG 管理者は可")
        void orgAdmin_allowed() {
            when(accessControlService.isAdminOrAbove(ORG_ADMIN, ORG_ID, "ORGANIZATION")).thenReturn(true);
            assertThat(service.canEnterScoreTournamentWide(ORG_ADMIN, ORG_ID, T_ID)).isTrue();
        }

        @Test
        @DisplayName("指名スコアキーパーは可")
        void scorekeeper_allowed() {
            when(scorekeeperRepository.existsByTournamentIdAndUserId(T_ID, SCOREKEEPER)).thenReturn(true);
            assertThat(service.canEnterScoreTournamentWide(SCOREKEEPER, ORG_ID, T_ID)).isTrue();
        }

        @Test
        @DisplayName("参加チーム ADMIN は batch では不可（条件③は対象外）")
        void participatingTeamAdmin_denied_for_batch() {
            when(accessControlService.isAdminOrAbove(HOME_TEAM_ADMIN, HOME_TEAM_ID, "TEAM")).thenReturn(true);
            assertThat(service.canEnterScoreTournamentWide(HOME_TEAM_ADMIN, ORG_ID, T_ID)).isFalse();
        }

        @Test
        @DisplayName("一般ユーザーは不可")
        void plainUser_denied() {
            assertThat(service.canEnterScoreTournamentWide(PLAIN_USER, ORG_ID, T_ID)).isFalse();
        }
    }
}
