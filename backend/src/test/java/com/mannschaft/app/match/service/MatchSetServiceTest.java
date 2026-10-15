package com.mannschaft.app.match.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.match.domain.MatchStatus;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.entity.MatchSetEntity;
import com.mannschaft.app.match.repository.MatchSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link MatchSetService} の純 UT（test-first・sports/04_volleyball.md §4 / 01 §B.5 / §B.1.2）。
 *
 * <p>セットスコア upsert・デュース判定によるセット勝者導出（winner_side）・獲得セット数からの
 * 試合スコア（matches.home_score/away_score）導出を、Mockito モックで検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchSetService（セットスコア記録・勝敗導出）UT")
class MatchSetServiceTest {

    private static final long ORG = 50L;
    private static final long TEAM = 100L;
    private static final long ACTOR = 1L;

    @Mock
    private MatchSetRepository matchSetRepository;
    @Mock
    private MatchService matchService;
    @Mock
    private MatchAccessService matchAccessService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private MatchSetService service;

    private UUID matchId;
    private MatchEntity match;
    private List<MatchSetEntity> store;

    @BeforeEach
    void setUp() {
        matchId = UUID.randomUUID();
        match = MatchEntity.builder()
                .organizationId(ORG)
                .teamId(TEAM)
                .sport(Sport.VOLLEYBALL)
                .status(MatchStatus.IN_PROGRESS)
                .periodFormat("BEST_OF_5")
                .createdBy(ACTOR)
                .build();
        match.setId(matchId);
        store = new ArrayList<>();

        lenient().when(matchService.getMatchOrThrow(matchId, ORG)).thenReturn(match);
        lenient().when(matchSetRepository.findByMatchIdAndSetNumber(any(), any()))
                .thenAnswer(inv -> store.stream()
                        .filter(s -> s.getSetNumber().equals(inv.getArgument(1)))
                        .findFirst());
        lenient().when(matchSetRepository.findByMatchIdOrderBySetNumberAsc(matchId))
                .thenReturn(store);
        lenient().when(matchSetRepository.save(any())).thenAnswer(inv -> {
            MatchSetEntity s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(UUID.randomUUID());
            }
            store.removeIf(e -> e.getSetNumber().equals(s.getSetNumber()));
            store.add(s);
            return s;
        });
    }

    @Test
    @DisplayName("非バレー競技でのセット記録は 400（MATCH_024・セット制のみ）")
    void rejectNonVolleyball() {
        match.setSport(Sport.SOCCER);
        assertThatThrownBy(() -> service.recordSet(matchId, ORG, ACTOR,
                cmd(1, 25, 23)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("デュース未決着（25-24）はセット勝者を確定しない（winner_side=null）")
    void deuceUndecidedNoWinner() {
        MatchSetEntity saved = service.recordSet(matchId, ORG, ACTOR, cmd(1, 25, 24));
        assertThat(saved.getWinnerSide()).isNull();
    }

    @Test
    @DisplayName("25-23 は HOME のセット勝者を確定する")
    void homeWinsSet() {
        MatchSetEntity saved = service.recordSet(matchId, ORG, ACTOR, cmd(1, 25, 23));
        assertThat(saved.getWinnerSide()).isEqualTo(TeamSide.HOME);
    }

    @Test
    @DisplayName("デュース 26-24 はセット勝者を確定する（24-24 から 2 点差）")
    void deuceDecidedWinner() {
        MatchSetEntity saved = service.recordSet(matchId, ORG, ACTOR, cmd(1, 26, 24));
        assertThat(saved.getWinnerSide()).isEqualTo(TeamSide.HOME);
    }

    @Test
    @DisplayName("第 5 セットは最終セット（15 点制）として is_final_set=true・15-13 で決着")
    void fifthSetIsFinal() {
        MatchSetEntity saved = service.recordSet(matchId, ORG, ACTOR, cmd(5, 15, 13));
        assertThat(saved.isFinalSet()).isTrue();
        assertThat(saved.getWinnerSide()).isEqualTo(TeamSide.HOME);
    }

    @Test
    @DisplayName("獲得セット数を matches.home_score/away_score に集計反映する（3-1）")
    void aggregatesWonSetsToMatchScore_3_1() {
        // HOME が 1,2,4 セット・AWAY が 3 セットを取る → 3-1
        service.recordSet(matchId, ORG, ACTOR, cmd(1, 25, 20)); // HOME
        service.recordSet(matchId, ORG, ACTOR, cmd(2, 25, 18)); // HOME
        service.recordSet(matchId, ORG, ACTOR, cmd(3, 22, 25)); // AWAY
        service.recordSet(matchId, ORG, ACTOR, cmd(4, 25, 23)); // HOME → 3 セット先取で決着

        assertThat(match.getHomeScore()).isEqualTo(3);
        assertThat(match.getAwayScore()).isEqualTo(1);
    }

    @Test
    @DisplayName("2-2 から第 5 セットで決着 → 3-2 に集計（引分けなし）")
    void aggregatesWonSetsToMatchScore_3_2() {
        service.recordSet(matchId, ORG, ACTOR, cmd(1, 25, 20)); // HOME
        service.recordSet(matchId, ORG, ACTOR, cmd(2, 20, 25)); // AWAY
        service.recordSet(matchId, ORG, ACTOR, cmd(3, 25, 22)); // HOME
        service.recordSet(matchId, ORG, ACTOR, cmd(4, 23, 25)); // AWAY → 2-2
        service.recordSet(matchId, ORG, ACTOR, cmd(5, 15, 12)); // HOME（最終 15 点）→ 3-2

        assertThat(match.getHomeScore()).isEqualTo(3);
        assertThat(match.getAwayScore()).isEqualTo(2);
    }

    @Test
    @DisplayName("未決着セットは獲得セット数に数えない（25-24 は 0 勝扱い）")
    void undecidedSetNotCounted() {
        service.recordSet(matchId, ORG, ACTOR, cmd(1, 25, 24)); // 未決着
        assertThat(match.getHomeScore()).isZero();
        assertThat(match.getAwayScore()).isZero();
    }

    @Test
    @DisplayName("同一 set_number の再記録は upsert（既存行を更新・新規行を作らない）")
    void upsertSameSetNumber() {
        MatchSetEntity first = service.recordSet(matchId, ORG, ACTOR, cmd(1, 20, 18));
        UUID firstId = first.getId();
        when(matchSetRepository.findByMatchIdAndSetNumber(matchId, 1))
                .thenReturn(Optional.of(first));

        MatchSetEntity updated = service.recordSet(matchId, ORG, ACTOR, cmd(1, 25, 23));
        assertThat(updated.getId()).isEqualTo(firstId);
        assertThat(updated.getHomePoints()).isEqualTo(25);
        assertThat(updated.getWinnerSide()).isEqualTo(TeamSide.HOME);
        assertThat(store).hasSize(1);
    }

    private MatchSetService.SetScoreCommand cmd(int setNumber, int home, int away) {
        return MatchSetService.SetScoreCommand.builder()
                .setNumber(setNumber)
                .homePoints(home)
                .awayPoints(away)
                .build();
    }
}
