package com.mannschaft.app.tournament.service;

import com.mannschaft.app.tournament.TournamentStatus;
import com.mannschaft.app.tournament.dto.DivisionLeaderProjection;
import com.mannschaft.app.tournament.dto.DivisionParticipantCountProjection;
import com.mannschaft.app.tournament.dto.OrganizationTournamentSummaryResponse;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.repository.TournamentStandingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F08.7.1 / 02 ②: {@link OrganizationTournamentSummaryService} の単体テスト。
 *
 * <p>設計書 docs/features/F08.7.1_tournament_extensions/02_dashboard_widgets.md §2.1 ② / §5.3 に準拠。
 * 集約結果・DRAFT 除外（非公開大会の非露出）・空状態・首位フォールバック・N+1 回避を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationTournamentSummaryService 単体テスト")
class OrganizationTournamentSummaryServiceTest {

    @Mock private TournamentRepository tournamentRepository;
    @Mock private TournamentDivisionRepository divisionRepository;
    @Mock private TournamentParticipantRepository participantRepository;
    @Mock private TournamentStandingRepository standingRepository;

    @InjectMocks
    private OrganizationTournamentSummaryService service;

    private static final Long ORG_ID = 100L;

    // ---- ヘルパー ----

    private TournamentEntity tournament(Long id, String name, TournamentStatus status) {
        TournamentEntity t = TournamentEntity.builder()
                .organizationId(ORG_ID)
                .name(name)
                .status(status)
                .build();
        ReflectionTestUtils.setField(t, "id", id);
        return t;
    }

    private TournamentDivisionEntity division(Long id, Long tournamentId, String name) {
        TournamentDivisionEntity d = TournamentDivisionEntity.builder()
                .tournamentId(tournamentId)
                .name(name)
                .build();
        ReflectionTestUtils.setField(d, "id", id);
        return d;
    }

    @Nested
    @DisplayName("getSummary: 集約結果")
    class Aggregation {

        @Test
        @DisplayName("正常系: 1大会×2部の首位名・参加数・status を集約して返す")
        void getSummary_集約() {
            // Given: 1大会（IN_PROGRESS）に 2 部
            TournamentEntity t = tournament(12L, "大分県リーグ 2026", TournamentStatus.IN_PROGRESS);
            when(tournamentRepository.findByOrganizationIdAndStatusNotOrderByCreatedAtDesc(
                    ORG_ID, TournamentStatus.DRAFT)).thenReturn(List.of(t));

            TournamentDivisionEntity d1 = division(30L, 12L, "1部");
            TournamentDivisionEntity d2 = division(31L, 12L, "2部");
            when(divisionRepository.findByTournamentIdInOrderByLevelAscSortOrderAsc(List.of(12L)))
                    .thenReturn(List.of(d1, d2));

            when(participantRepository.countParticipantsByDivisionIdIn(List.of(30L, 31L)))
                    .thenReturn(List.of(
                            new DivisionParticipantCountProjection(30L, 8L),
                            new DivisionParticipantCountProjection(31L, 8L)));

            when(standingRepository.findLeadersByDivisionIdIn(List.of(30L, 31L)))
                    .thenReturn(List.of(
                            new DivisionLeaderProjection(30L, 500L, "FC大分"),
                            new DivisionLeaderProjection(31L, 501L, "別府SC")));

            // When
            OrganizationTournamentSummaryResponse res = service.getSummary(ORG_ID);

            // Then
            assertThat(res.getTournaments()).hasSize(1);
            var entry = res.getTournaments().get(0);
            assertThat(entry.getTournamentId()).isEqualTo(12L);
            assertThat(entry.getName()).isEqualTo("大分県リーグ 2026");
            assertThat(entry.getStatus()).isEqualTo("IN_PROGRESS");
            assertThat(entry.getDivisions()).hasSize(2);

            var div1 = entry.getDivisions().get(0);
            assertThat(div1.divisionId()).isEqualTo(30L);
            assertThat(div1.name()).isEqualTo("1部");
            assertThat(div1.participantCount()).isEqualTo(8);
            assertThat(div1.leaderTeamName()).isEqualTo("FC大分");

            var div2 = entry.getDivisions().get(1);
            assertThat(div2.divisionId()).isEqualTo(31L);
            assertThat(div2.leaderTeamName()).isEqualTo("別府SC");
        }

        @Test
        @DisplayName("正常系: 順位未計算（standing 不在）の部は首位 null・参加0件の部は count 0")
        void getSummary_首位nullと参加0() {
            TournamentEntity t = tournament(12L, "新規大会", TournamentStatus.OPEN);
            when(tournamentRepository.findByOrganizationIdAndStatusNotOrderByCreatedAtDesc(
                    ORG_ID, TournamentStatus.DRAFT)).thenReturn(List.of(t));

            TournamentDivisionEntity d1 = division(30L, 12L, "1部");
            when(divisionRepository.findByTournamentIdInOrderByLevelAscSortOrderAsc(List.of(12L)))
                    .thenReturn(List.of(d1));

            // 参加 0 件（集約結果に該当 division なし）・首位なし
            when(participantRepository.countParticipantsByDivisionIdIn(List.of(30L)))
                    .thenReturn(List.of());
            when(standingRepository.findLeadersByDivisionIdIn(List.of(30L)))
                    .thenReturn(List.of());

            OrganizationTournamentSummaryResponse res = service.getSummary(ORG_ID);

            var div = res.getTournaments().get(0).getDivisions().get(0);
            assertThat(div.participantCount()).isEqualTo(0);
            assertThat(div.leaderTeamName()).isNull();
        }

        @Test
        @DisplayName("首位の displayName が空なら 'Team {teamId}' にフォールバックする")
        void getSummary_首位名フォールバック() {
            TournamentEntity t = tournament(12L, "大会", TournamentStatus.IN_PROGRESS);
            when(tournamentRepository.findByOrganizationIdAndStatusNotOrderByCreatedAtDesc(
                    ORG_ID, TournamentStatus.DRAFT)).thenReturn(List.of(t));
            TournamentDivisionEntity d1 = division(30L, 12L, "1部");
            when(divisionRepository.findByTournamentIdInOrderByLevelAscSortOrderAsc(List.of(12L)))
                    .thenReturn(List.of(d1));
            when(participantRepository.countParticipantsByDivisionIdIn(List.of(30L)))
                    .thenReturn(List.of(new DivisionParticipantCountProjection(30L, 4L)));
            when(standingRepository.findLeadersByDivisionIdIn(List.of(30L)))
                    .thenReturn(List.of(new DivisionLeaderProjection(30L, 777L, "  ")));

            OrganizationTournamentSummaryResponse res = service.getSummary(ORG_ID);

            assertThat(res.getTournaments().get(0).getDivisions().get(0).leaderTeamName())
                    .isEqualTo("Team 777");
        }
    }

    @Nested
    @DisplayName("getSummary: セキュリティ（§5.3 非公開大会の非露出）")
    class Security {

        @Test
        @DisplayName("DRAFT 大会はリポジトリ層クエリで除外される（excludeStatus=DRAFT を渡す）")
        void getSummary_DRAFT除外() {
            when(tournamentRepository.findByOrganizationIdAndStatusNotOrderByCreatedAtDesc(
                    ORG_ID, TournamentStatus.DRAFT)).thenReturn(List.of());

            OrganizationTournamentSummaryResponse res = service.getSummary(ORG_ID);

            assertThat(res.getTournaments()).isEmpty();
            // DRAFT 除外のステータスを必ず渡していること（漏洩防止の根拠）
            verify(tournamentRepository)
                    .findByOrganizationIdAndStatusNotOrderByCreatedAtDesc(ORG_ID, TournamentStatus.DRAFT);
        }
    }

    @Nested
    @DisplayName("getSummary: 空状態・N+1 回避")
    class EmptyAndNPlusOne {

        @Test
        @DisplayName("主催大会 0 件なら空一覧を返し、後続クエリを撃たない")
        void getSummary_大会0件() {
            when(tournamentRepository.findByOrganizationIdAndStatusNotOrderByCreatedAtDesc(
                    ORG_ID, TournamentStatus.DRAFT)).thenReturn(List.of());

            OrganizationTournamentSummaryResponse res = service.getSummary(ORG_ID);

            assertThat(res.getTournaments()).isEmpty();
            verify(divisionRepository, never()).findByTournamentIdInOrderByLevelAscSortOrderAsc(anyCollection());
            verify(participantRepository, never()).countParticipantsByDivisionIdIn(anyCollection());
            verify(standingRepository, never()).findLeadersByDivisionIdIn(anyCollection());
        }

        @Test
        @DisplayName("N+1 回避: 大会数に関わらず集約クエリは各 1 回のみ（IN 句バッチ）")
        void getSummary_集約クエリは各1回() {
            TournamentEntity t1 = tournament(1L, "A", TournamentStatus.IN_PROGRESS);
            TournamentEntity t2 = tournament(2L, "B", TournamentStatus.COMPLETED);
            when(tournamentRepository.findByOrganizationIdAndStatusNotOrderByCreatedAtDesc(
                    ORG_ID, TournamentStatus.DRAFT)).thenReturn(List.of(t1, t2));

            TournamentDivisionEntity d1 = division(10L, 1L, "1部");
            TournamentDivisionEntity d2 = division(20L, 2L, "1部");
            when(divisionRepository.findByTournamentIdInOrderByLevelAscSortOrderAsc(List.of(1L, 2L)))
                    .thenReturn(List.of(d1, d2));
            when(participantRepository.countParticipantsByDivisionIdIn(List.of(10L, 20L)))
                    .thenReturn(List.of());
            when(standingRepository.findLeadersByDivisionIdIn(List.of(10L, 20L)))
                    .thenReturn(List.of());

            service.getSummary(ORG_ID);

            // ディビジョン取得・参加数集約・首位取得はそれぞれ 1 回のみ（大会ごとに撃たない）
            verify(divisionRepository).findByTournamentIdInOrderByLevelAscSortOrderAsc(List.of(1L, 2L));
            verify(participantRepository).countParticipantsByDivisionIdIn(List.of(10L, 20L));
            verify(standingRepository).findLeadersByDivisionIdIn(List.of(10L, 20L));
        }
    }
}
