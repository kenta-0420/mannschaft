package com.mannschaft.app.match.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.match.MatchErrorCode;
import com.mannschaft.app.match.domain.MatchStatus;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.domain.StateModel;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.entity.MatchScoreEntryEntity;
import com.mannschaft.app.match.live.MatchLiveUpdateEvent;
import com.mannschaft.app.match.repository.MatchRepository;
import com.mannschaft.app.match.repository.MatchScoreEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MatchScoreEntryService} の純 UT
 * （test-first・sports/07_scored.md §5B / §6 / §11 / 01 §B.1.2 / §D.8）。
 *
 * <p>多人数順位制：N 人の合計点記録→順位算出（合計点降順・同点同順位 1,2,2,4）・補助 home_score 再導出
 * （二層正本・§5B.2）・採点競技以外の拒否（MATCH_029）・出場者識別/非負検証（MATCH_024）・
 * 採点改竄防止の監査記録・観戦配信・2 段アクセス（match_id スコープ）を Mockito モックで検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchScoreEntryService（多人数順位制・N人スコア→順位算出）UT")
class MatchScoreEntryServiceTest {

    private static final long ORG = 50L;
    private static final long TEAM = 100L;
    private static final long ACTOR = 1L;

    @Mock
    private MatchScoreEntryRepository entryRepository;
    @Mock
    private MatchRepository matchRepository;
    @Mock
    private MatchService matchService;
    @Mock
    private MatchAccessService matchAccessService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private MatchScoreEntryService service;

    private UUID matchId;
    private MatchEntity match;

    @BeforeEach
    void setUp() {
        matchId = UUID.randomUUID();
        match = MatchEntity.builder()
                .organizationId(ORG)
                .teamId(TEAM)
                .sport(Sport.FIGURE_SKATING)
                .stateModel(StateModel.SCORED)
                .status(MatchStatus.IN_PROGRESS)
                .createdBy(ACTOR)
                .build();
        match.setId(matchId);
        lenient().when(matchService.getMatchOrThrow(matchId, ORG)).thenReturn(match);
        lenient().when(matchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // save は @PrePersist を呼ばない（モック）。順位検証用に保存対象をそのまま返す。
        lenient().when(entryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private MatchScoreEntryService.ScoreEntryLine line(String name, int totalScaled) {
        return MatchScoreEntryService.ScoreEntryLine.builder()
                .competitorName(name)
                .totalScaled(totalScaled)
                .build();
    }

    private MatchScoreEntryService.ScoreEntryLine userLine(Long userId, int totalScaled) {
        return MatchScoreEntryService.ScoreEntryLine.builder()
                .competitorUserId(userId)
                .totalScaled(totalScaled)
                .build();
    }

    private MatchScoreEntryService.ScoreEntriesCommand cmd(MatchScoreEntryService.ScoreEntryLine... lines) {
        return MatchScoreEntryService.ScoreEntriesCommand.builder()
                .lines(List.of(lines))
                .build();
    }

    /** 名前→順位のルックアップ（結果一覧から）。 */
    private Integer rankOf(List<MatchScoreEntryEntity> result, String name) {
        return result.stream()
                .filter(e -> name.equals(e.getCompetitorName()))
                .map(MatchScoreEntryEntity::getRankPosition)
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("N人の合計点を降順で順位付けする（1,2,3・最高点が1位）")
    void ranksDescendingByScore() {
        List<MatchScoreEntryEntity> result = service.recordEntries(matchId, ORG, ACTOR, cmd(
                line("A", 180000),
                line("B", 210000),
                line("C", 195000)));

        assertThat(rankOf(result, "B")).isEqualTo(1); // 210000
        assertThat(rankOf(result, "C")).isEqualTo(2); // 195000
        assertThat(rankOf(result, "A")).isEqualTo(3); // 180000
        // 全置換: 既存削除 → 3 件保存
        verify(entryRepository).deleteByMatchId(matchId);
        verify(entryRepository, times(3)).save(any());
    }

    @Test
    @DisplayName("同点は同順位とし次順位を飛ばす標準ルール（1,2,2,4）")
    void tiesShareRankAndSkipNext() {
        List<MatchScoreEntryEntity> result = service.recordEntries(matchId, ORG, ACTOR, cmd(
                line("A", 100000),
                line("B", 90000),
                line("C", 90000),
                line("D", 80000)));

        assertThat(rankOf(result, "A")).isEqualTo(1);
        assertThat(rankOf(result, "B")).isEqualTo(2);
        assertThat(rankOf(result, "C")).isEqualTo(2); // 同点同順位
        assertThat(rankOf(result, "D")).isEqualTo(4); // 3 を飛ばす
    }

    @Test
    @DisplayName("3 者同点は全員同順位（1,1,1,4）")
    void threeWayTie() {
        List<MatchScoreEntryEntity> result = service.recordEntries(matchId, ORG, ACTOR, cmd(
                line("A", 90000),
                line("B", 90000),
                line("C", 90000),
                line("D", 50000)));

        assertThat(rankOf(result, "A")).isEqualTo(1);
        assertThat(rankOf(result, "B")).isEqualTo(1);
        assertThat(rankOf(result, "C")).isEqualTo(1);
        assertThat(rankOf(result, "D")).isEqualTo(4);
    }

    @Test
    @DisplayName("結果は順位昇順で返す（順位表向き・1位が先頭）")
    void resultsSortedByRankAscending() {
        List<MatchScoreEntryEntity> result = service.recordEntries(matchId, ORG, ACTOR, cmd(
                line("low", 100000),
                line("high", 300000),
                line("mid", 200000)));

        assertThat(result).extracting(MatchScoreEntryEntity::getRankPosition)
                .containsExactly(1, 2, 3);
        assertThat(result.get(0).getCompetitorName()).isEqualTo("high");
    }

    @Test
    @DisplayName("補助として最上位エントリの合計点を home_score へ再導出（二層正本・§5B.2）、away_score は 0")
    void reflectsTopScoreToHomeScore() {
        service.recordEntries(matchId, ORG, ACTOR, cmd(
                line("A", 180000),
                line("B", 210000),
                line("C", 195000)));

        assertThat(match.getHomeScore()).isEqualTo(210000); // 最上位
        assertThat(match.getAwayScore()).isZero();
        assertThat(match.getWinMethod()).isNull();
    }

    @Test
    @DisplayName("登録選手（competitor_user_id）でも記録できる")
    void acceptsRegisteredUserEntries() {
        List<MatchScoreEntryEntity> result = service.recordEntries(matchId, ORG, ACTOR, cmd(
                userLine(1001L, 150000),
                userLine(1002L, 160000)));
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getCompetitorUserId()).isEqualTo(1002L); // 1位
        assertThat(result.get(0).getRankPosition()).isEqualTo(1);
    }

    @Test
    @DisplayName("採点記録は assertCanEditMeta（採点改竄防止権限）へ委譲する（§11 / 03 §C.7）")
    void delegatesToAssertCanEditMeta() {
        service.recordEntries(matchId, ORG, ACTOR, cmd(line("A", 100000), line("B", 90000)));
        verify(matchAccessService).assertCanEditMeta(ACTOR, match);
    }

    @Test
    @DisplayName("再導出した最上位合計点を before/after で監査記録する（MATCH_SCORE_FINALIZED・改竄防止）")
    void recordsAudit() {
        match.setHomeScore(1);
        match.setAwayScore(2);
        service.recordEntries(matchId, ORG, ACTOR, cmd(line("A", 100000), line("B", 90000)));
        verify(auditLogService).record(
                eq(AuditEventType.MATCH_SCORE_FINALIZED.name()), eq(ACTOR), isNull(),
                eq(TEAM), eq(ORG), isNull(), isNull(), isNull(), any());
    }

    @Test
    @DisplayName("記録後にスコア（順位）更新を観戦者へ配信する（§9・コミット後）")
    void publishesLiveScoreUpdate() {
        service.recordEntries(matchId, ORG, ACTOR, cmd(line("A", 100000)));
        verify(eventPublisher).publishEvent(any(MatchLiveUpdateEvent.class));
    }

    @Test
    @DisplayName("採点競技（SCORED）以外への記録は 400（MATCH_029・症状を隠さない・共存=既存2者経路を壊さない）")
    void rejectsNonScoredSport() {
        match.setSport(Sport.SOCCER);
        match.setStateModel(StateModel.CONTINUOUS_TIME);
        assertThatThrownBy(() -> service.recordEntries(matchId, ORG, ACTOR, cmd(line("A", 100000))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_029);
        verify(entryRepository, never()).save(any());
        verify(matchRepository, never()).save(any());
    }

    @Test
    @DisplayName("体操（GYMNASTICS）も多人数順位制で記録できる（採点競技共通経路）")
    void acceptsGymnastics() {
        match.setSport(Sport.GYMNASTICS);
        List<MatchScoreEntryEntity> result = service.recordEntries(matchId, ORG, ACTOR, cmd(
                line("A", 85332),
                line("B", 86000)));
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getRankPosition()).isEqualTo(1);
    }

    @Test
    @DisplayName("出場者識別（user/team/name のいずれか）が無い行は 400（MATCH_024）")
    void rejectsEntryWithoutIdentity() {
        MatchScoreEntryService.ScoreEntryLine anon =
                MatchScoreEntryService.ScoreEntryLine.builder().totalScaled(100000).build();
        assertThatThrownBy(() -> service.recordEntries(matchId, ORG, ACTOR, cmd(anon)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_024);
        verify(matchRepository, never()).save(any());
    }

    @Test
    @DisplayName("負の total_scaled は 400（整数スケール×1000 は非負・MATCH_024）")
    void rejectsNegativeTotal() {
        assertThatThrownBy(() -> service.recordEntries(matchId, ORG, ACTOR, cmd(line("A", -1))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_024);
        verify(matchRepository, never()).save(any());
    }

    @Test
    @DisplayName("空のエントリは 400（MATCH_024）")
    void rejectsEmptyEntries() {
        MatchScoreEntryService.ScoreEntriesCommand empty =
                MatchScoreEntryService.ScoreEntriesCommand.builder().lines(List.of()).build();
        assertThatThrownBy(() -> service.recordEntries(matchId, ORG, ACTOR, empty))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_024);
        verify(matchRepository, never()).save(any());
    }

    @Test
    @DisplayName("listEntries は match_id スコープで取得する（親テナントゲート後の二段アクセス・IDOR）")
    void listScopedByMatchId() {
        when(entryRepository.findByMatchIdOrderByRankPositionAscTotalScaledDesc(matchId))
                .thenReturn(List.of());
        service.listEntries(matchId, ORG);
        verify(matchService).getMatchOrThrow(matchId, ORG);
        verify(entryRepository).findByMatchIdOrderByRankPositionAscTotalScaledDesc(matchId);
    }

    @Test
    @DisplayName("state_model 未設定（旧レコード）でも sport から SCORED 導出して記録できる")
    void resolvesStateModelFromSportWhenColumnNull() {
        match.setStateModel(null);
        match.setSport(Sport.FIGURE_SKATING);
        List<MatchScoreEntryEntity> result =
                service.recordEntries(matchId, ORG, ACTOR, cmd(line("A", 100000)));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRankPosition()).isEqualTo(1);
    }
}
