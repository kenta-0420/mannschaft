package com.mannschaft.app.match.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.match.MatchCompletedEvent;
import com.mannschaft.app.match.MatchErrorCode;
import com.mannschaft.app.match.domain.MatchKind;
import com.mannschaft.app.match.domain.MatchStatus;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.dto.MatchSummaryResponse;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.repository.MatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MatchService} の純 UT（test-first・02 §E.3 / 03 §C.2/C.7 / 05 §H.2）。
 *
 * <p>COMPLETED 遷移時の duration 必須化・確定再計算・{@link MatchCompletedEvent} 発火、
 * finalizeScore の認可委譲＋before/after 監査、記録モード/記録係変更時のみの監査記録 を実アサートする。
 * 依存はすべて Mockito モック（純 Service 層・@WebMvcTest は使わない）。</p>
 */
@ExtendWith(MockitoExtension.class)
class MatchServiceTest {

    private static final long ORG = 50L;
    private static final long TEAM = 100L;
    private static final long ACTOR = 1L;

    @Mock
    private MatchRepository matchRepository;
    @Mock
    private MatchAccessService matchAccessService;
    @Mock
    private PlayingTimeCalculationService playingTimeCalculationService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private MatchService service;

    private UUID matchId;
    private MatchEntity match;

    @BeforeEach
    void setUp() {
        matchId = UUID.randomUUID();
        match = MatchEntity.builder()
                .organizationId(ORG)
                .teamId(TEAM)
                .sport(Sport.SOCCER)
                .status(MatchStatus.IN_PROGRESS)
                .createdBy(ACTOR)
                .build();
        match.setId(matchId);
        lenient().when(matchRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(matchId, ORG))
                .thenReturn(Optional.of(match));
        lenient().when(matchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ─── (a) COMPLETED 遷移で duration 未設定 → 400 ──────────────

    @Test
    @DisplayName("(a) COMPLETED 遷移で duration_minutes 未設定なら 400（MATCH_023・02 §E.3）")
    void completedWithoutDurationIs400() {
        match.setDurationMinutes(null);
        assertThatThrownBy(() -> service.changeStatus(matchId, ORG, ACTOR, MatchStatus.COMPLETED))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("試合時間");
        // 400 で弾かれた場合は再計算もイベント発火もしない（症状を隠さない）
        verify(playingTimeCalculationService, never()).recalculate(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ─── (b) COMPLETED 遷移時のみ MatchCompletedEvent 発火＋確定再計算 ──

    @Test
    @DisplayName("(b) COMPLETED 遷移で MatchCompletedEvent 発火＋全 side 確定再計算（recalculate(match, null)）")
    void completedPublishesEventAndRecalculates() {
        match.setDurationMinutes(90);
        match.setTournamentFixtureId(777L);
        match.setHomeScore(2);
        match.setAwayScore(1);
        // 延長同点後の PK 戦（本戦 2-1 だが PK は別軸・F08.10 ② 順位連携）。
        // changeStatus(COMPLETED) は保存済み Entity の PK を MatchCompletedEvent に載せる必要がある。
        match.setHomePenaltyScore(5);
        match.setAwayPenaltyScore(4);

        service.changeStatus(matchId, ORG, ACTOR, MatchStatus.COMPLETED);

        // 確定再計算は全 side（editableTeamSides=null）
        verify(playingTimeCalculationService).recalculate(eq(match), isNull());

        // COMPLETED 遷移では 2 件 publish される: 順位連携の MatchCompletedEvent と
        // ライブ配信の MatchLiveUpdateEvent(STATUS_CHANGED・07 §J.2)。前者を抽出して検証する。
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        MatchCompletedEvent ev = captor.getAllValues().stream()
                .filter(o -> o instanceof MatchCompletedEvent)
                .map(o -> (MatchCompletedEvent) o)
                .findFirst()
                .orElseThrow(() -> new AssertionError("MatchCompletedEvent が publish されていない"));
        // ライブ配信 STATUS_CHANGED も併せて publish される（07 §J.2）
        boolean liveStatusPublished = captor.getAllValues().stream()
                .anyMatch(o -> o instanceof com.mannschaft.app.match.live.MatchLiveUpdateEvent
                        && ((com.mannschaft.app.match.live.MatchLiveUpdateEvent) o).getType()
                            == com.mannschaft.app.match.live.MatchLiveUpdateType.STATUS_CHANGED);
        assertThat(liveStatusPublished).isTrue();
        assertThat(ev.getMatchId()).isEqualTo(matchId);
        assertThat(ev.getTournamentFixtureId()).isEqualTo(777L);
        assertThat(ev.getHomeScore()).isEqualTo(2);
        assertThat(ev.getAwayScore()).isEqualTo(1);
        // PK 戦スコアが本戦と分離して event に載る（tournament/MatchScoreFixtureListener #1444 が
        // PK 勝敗を fixture 順位へ反映する経路の前提）。
        assertThat(ev.getHomePenaltyScore()).isEqualTo(5);
        assertThat(ev.getAwayPenaltyScore()).isEqualTo(4);
        assertThat(ev.getStatus()).isEqualTo(MatchStatus.COMPLETED);
    }

    @Test
    @DisplayName("(b) COMPLETED 以外の遷移では MatchCompletedEvent を発火しない・再計算もしない（ライブ配信 STATUS_CHANGED は発火）")
    void nonCompletedDoesNotPublishOrRecalculate() {
        service.changeStatus(matchId, ORG, ACTOR, MatchStatus.POSTPONED);

        // COMPLETED でない遷移では順位連携 MatchCompletedEvent は発火しない・確定再計算もしない
        verify(eventPublisher, never()).publishEvent(any(MatchCompletedEvent.class));
        verify(playingTimeCalculationService, never()).recalculate(any(), any());
        // ただしライブ配信の STATUS_CHANGED は全遷移で発火する（07 §J.2・観戦者へ進行を伝える）
        ArgumentCaptor<com.mannschaft.app.match.live.MatchLiveUpdateEvent> liveCaptor =
                ArgumentCaptor.forClass(com.mannschaft.app.match.live.MatchLiveUpdateEvent.class);
        verify(eventPublisher).publishEvent(liveCaptor.capture());
        assertThat(liveCaptor.getValue().getType())
                .isEqualTo(com.mannschaft.app.match.live.MatchLiveUpdateType.STATUS_CHANGED);
        assertThat(liveCaptor.getValue().getStatus()).isEqualTo(MatchStatus.POSTPONED);
        // 全遷移は監査記録される（03 §C.7）
        verify(auditLogService).record(eq(AuditEventType.MATCH_STATUS_CHANGED.name()),
                eq(ACTOR), any(), eq(TEAM), eq(ORG), any(), any(), any(), any());
    }

    @Test
    @DisplayName("(b) status 遷移は MatchAccessService.assertCanEditMeta に認可委譲する")
    void changeStatusDelegatesAuthz() {
        service.changeStatus(matchId, ORG, ACTOR, MatchStatus.POSTPONED);
        verify(matchAccessService).assertCanEditMeta(ACTOR, match);
    }

    // ─── (c) finalizeScore の認可委譲＋before/after 監査 ──────────

    @Test
    @DisplayName("(c) finalizeScore は認可委譲し、before/after・matchId・操作者・teamId を監査 metadata に記録")
    void finalizeScoreAuthzAndAudit() {
        // before スコア
        match.setHomeScore(0);
        match.setAwayScore(0);
        match.setHomePenaltyScore(null);
        match.setAwayPenaltyScore(null);

        service.finalizeScore(matchId, ORG, ACTOR, 3, 2, 5, 4);

        // 認可委譲
        verify(matchAccessService).assertCanEditMeta(ACTOR, match);
        // after が反映される
        assertThat(match.getHomeScore()).isEqualTo(3);
        assertThat(match.getAwayScore()).isEqualTo(2);
        assertThat(match.getHomePenaltyScore()).isEqualTo(5);
        assertThat(match.getAwayPenaltyScore()).isEqualTo(4);

        ArgumentCaptor<String> metaCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(eq(AuditEventType.MATCH_SCORE_FINALIZED.name()),
                eq(ACTOR), isNull(), eq(TEAM), eq(ORG),
                isNull(), isNull(), isNull(), metaCaptor.capture());
        String metadata = metaCaptor.getValue();
        // matchId・teamId
        assertThat(metadata).contains(matchId.toString());
        assertThat(metadata).contains("\"teamId\":" + TEAM);
        // before（0/0/null/null）と after（3/2/5/4）の両方が記録される
        assertThat(metadata).contains("\"before\":{\"home\":0,\"away\":0,\"homePk\":null,\"awayPk\":null}");
        assertThat(metadata).contains("\"after\":{\"home\":3,\"away\":2,\"homePk\":5,\"awayPk\":4}");
    }

    // ─── (d) 記録モード/記録係変更時のみ監査記録 ────────────────

    @Test
    @DisplayName("(d) モード切替（共同記録→公式戦）かつ記録係セット時のみ両イベントを記録")
    void changeRecordingModeRecordsOnlyWhenChanged() {
        match.setHasScorekeeper(false);
        match.setScorekeeperUserId(null);

        service.changeRecordingMode(matchId, ORG, ACTOR, true, 9L);

        // モード変更（false→true）を記録
        verify(auditLogService).record(eq(AuditEventType.MATCH_RECORDING_MODE_CHANGED.name()),
                eq(ACTOR), any(), eq(TEAM), eq(ORG), any(), any(), any(), any());
        // 記録係変更（null→9）を記録
        verify(auditLogService).record(eq(AuditEventType.MATCH_SCOREKEEPER_CHANGED.name()),
                eq(ACTOR), eq(9L), eq(TEAM), eq(ORG), any(), any(), any(), any());
    }

    @Test
    @DisplayName("(d) 変更がない場合（同一モード・同一記録係）は監査記録しない")
    void changeRecordingModeNoChangeNoAudit() {
        match.setHasScorekeeper(true);
        match.setScorekeeperUserId(9L);

        // 同じ値で呼ぶ → 変更なし
        service.changeRecordingMode(matchId, ORG, ACTOR, true, 9L);

        verify(auditLogService, never()).record(eq(AuditEventType.MATCH_RECORDING_MODE_CHANGED.name()),
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(auditLogService, never()).record(eq(AuditEventType.MATCH_SCOREKEEPER_CHANGED.name()),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("(d) 公式戦→共同記録（true→false）はモード変更を記録し scorekeeper を null 化")
    void changeRecordingModeToCoop() {
        match.setHasScorekeeper(true);
        match.setScorekeeperUserId(9L);

        service.changeRecordingMode(matchId, ORG, ACTOR, false, null);

        assertThat(match.getScorekeeperUserId()).isNull();
        verify(auditLogService).record(eq(AuditEventType.MATCH_RECORDING_MODE_CHANGED.name()),
                eq(ACTOR), any(), eq(TEAM), eq(ORG), any(), any(), any(), any());
        // 記録係 9→null も変更ありなので記録される
        verify(auditLogService).record(eq(AuditEventType.MATCH_SCOREKEEPER_CHANGED.name()),
                eq(ACTOR), isNull(), eq(TEAM), eq(ORG), any(), any(), any(), any());
    }

    @Test
    @DisplayName("(d) 記録モード切替は認可委譲する")
    void changeRecordingModeDelegatesAuthz() {
        match.setHasScorekeeper(false);
        service.changeRecordingMode(matchId, ORG, ACTOR, false, null);
        verify(matchAccessService).assertCanEditMeta(ACTOR, match);
    }

    // ─── create バリデーション（最小必須） ────────────────────

    @Test
    @DisplayName("create: kind が null なら 400（MATCH_024）")
    void createWithoutKind400() {
        MatchService.CreateCommand cmd = MatchService.CreateCommand.builder()
                .organizationId(ORG).teamId(TEAM).createdBy(ACTOR)
                .opponentName("相手FC").build();
        assertThatThrownBy(() -> service.create(cmd, ACTOR))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("create: 相手（opponentTeamId / opponentName）が両方欠落なら 400")
    void createWithoutOpponent400() {
        MatchService.CreateCommand cmd = MatchService.CreateCommand.builder()
                .organizationId(ORG).teamId(TEAM).createdBy(ACTOR)
                .kind(com.mannschaft.app.match.domain.MatchKind.PRACTICE)
                .build();
        assertThatThrownBy(() -> service.create(cmd, ACTOR))
                .isInstanceOf(BusinessException.class);
    }

    // ─── softDelete 認可委譲 ──────────────────────────────────

    @Test
    @DisplayName("softDelete は認可委譲し deleted_at をセットする")
    void softDeleteDelegatesAndMarks() {
        service.softDelete(matchId, ORG, ACTOR);
        verify(matchAccessService).assertCanEditMeta(ACTOR, match);
        assertThat(match.getDeletedAt()).isNotNull();
    }

    // ─── listMatches（一覧・Phase2C） ──────────────────────────

    @Test
    @DisplayName("listMatches: 認可委譲（assertCanListTeamMatches）＋テナント/チーム＋フィルタをリポジトリに渡し DTO へ変換")
    void listMatchesDelegatesAuthzAndPassesFilters() {
        LocalDateTime from = LocalDateTime.parse("2026-01-01T00:00:00");
        LocalDateTime to = LocalDateTime.parse("2026-12-31T23:59:59");
        MatchEntity row = MatchEntity.builder()
                .organizationId(ORG).teamId(TEAM).sport(Sport.SOCCER)
                .kind(MatchKind.TOURNAMENT).status(MatchStatus.COMPLETED).createdBy(ACTOR)
                .opponentName("相手FC").build();
        row.setId(UUID.randomUUID());
        Pageable pageable = PageRequest.of(0, 20);
        when(matchRepository.findTeamMatches(
                eq(ORG), eq(TEAM), eq(MatchStatus.COMPLETED), eq(MatchKind.TOURNAMENT),
                eq(Sport.SOCCER), eq(from), eq(to), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(row), pageable, 1));

        MatchService.ListFilter filter = MatchService.ListFilter.builder()
                .status(MatchStatus.COMPLETED).kind(MatchKind.TOURNAMENT).sport(Sport.SOCCER)
                .from(from).to(to).build();

        var result = service.listMatches(ORG, TEAM, ACTOR, filter, pageable);

        // 認可委譲（第一防御）
        verify(matchAccessService).assertCanListTeamMatches(ACTOR, TEAM);
        // Entity → サマリ DTO 変換
        assertThat(result.getTotalElements()).isEqualTo(1);
        MatchSummaryResponse dto = result.getContent().get(0);
        assertThat(dto.getId()).isEqualTo(row.getId());
        assertThat(dto.getStatus()).isEqualTo(MatchStatus.COMPLETED);
        assertThat(dto.getKind()).isEqualTo(MatchKind.TOURNAMENT);
        assertThat(dto.getOpponentName()).isEqualTo("相手FC");
    }

    @Test
    @DisplayName("listMatches: 非メンバー（認可 403）ならリポジトリを呼ばずに伝播する")
    void listMatchesNonMemberThrows() {
        doThrow(new BusinessException(MatchErrorCode.MATCH_010))
                .when(matchAccessService).assertCanListTeamMatches(ACTOR, TEAM);

        assertThatThrownBy(() -> service.listMatches(ORG, TEAM, ACTOR,
                MatchService.ListFilter.builder().build(), PageRequest.of(0, 20)))
                .isInstanceOf(BusinessException.class);

        verify(matchRepository, never()).findTeamMatches(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("listMatches: filter が null でも全 null フィルタとしてリポジトリに渡す（NPE にならない）")
    void listMatchesNullFilterDefaultsToAllNull() {
        Pageable pageable = PageRequest.of(0, 20);
        when(matchRepository.findTeamMatches(
                eq(ORG), eq(TEAM), isNull(), isNull(), isNull(), isNull(), isNull(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        var result = service.listMatches(ORG, TEAM, ACTOR, null, pageable);

        assertThat(result.getTotalElements()).isZero();
        verify(matchRepository).findTeamMatches(
                eq(ORG), eq(TEAM), isNull(), isNull(), isNull(), isNull(), isNull(), eq(pageable));
    }

    // ─── resolveByScheduleId（入口④・予定からの解決・二重起票防止） ────

    @Test
    @DisplayName("resolveByScheduleId: 既存試合があれば認可委譲のうえサマリ DTO を返す")
    void resolveByScheduleIdReturnsExisting() {
        long scheduleId = 9001L;
        MatchEntity existing = MatchEntity.builder()
                .organizationId(ORG).teamId(TEAM).sport(Sport.SOCCER)
                .kind(MatchKind.PRACTICE).status(MatchStatus.SCHEDULED).createdBy(ACTOR)
                .scheduleId(scheduleId).opponentName("相手FC").build();
        existing.setId(UUID.randomUUID());
        when(matchRepository
                .findFirstByOrganizationIdAndTeamIdAndScheduleIdOrderByKickoffAtDescIdDesc(ORG, TEAM, scheduleId))
                .thenReturn(Optional.of(existing));

        var result = service.resolveByScheduleId(ORG, TEAM, ACTOR, scheduleId);

        // 認可委譲（一覧と同水準のメンバー以上）
        verify(matchAccessService).assertCanListTeamMatches(ACTOR, TEAM);
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(existing.getId());
        assertThat(result.get().getOpponentName()).isEqualTo("相手FC");
    }

    @Test
    @DisplayName("resolveByScheduleId: 既存が無ければ Optional.empty（FE は作成へ分岐）")
    void resolveByScheduleIdEmptyWhenNone() {
        long scheduleId = 9002L;
        when(matchRepository
                .findFirstByOrganizationIdAndTeamIdAndScheduleIdOrderByKickoffAtDescIdDesc(ORG, TEAM, scheduleId))
                .thenReturn(Optional.empty());

        var result = service.resolveByScheduleId(ORG, TEAM, ACTOR, scheduleId);

        verify(matchAccessService).assertCanListTeamMatches(ACTOR, TEAM);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("resolveByScheduleId: 非メンバー（認可 403）ならリポジトリを呼ばずに伝播する")
    void resolveByScheduleIdNonMemberThrows() {
        doThrow(new BusinessException(MatchErrorCode.MATCH_010))
                .when(matchAccessService).assertCanListTeamMatches(ACTOR, TEAM);

        assertThatThrownBy(() -> service.resolveByScheduleId(ORG, TEAM, ACTOR, 9003L))
                .isInstanceOf(BusinessException.class);

        verify(matchRepository, never())
                .findFirstByOrganizationIdAndTeamIdAndScheduleIdOrderByKickoffAtDescIdDesc(any(), any(), any());
    }

    // ─── resolveByFixtureId（入口①・大会の対戦カードからの解決・二重起票防止） ────

    @Test
    @DisplayName("resolveByFixtureId: 既存試合があれば認可委譲のうえサマリ DTO を返す")
    void resolveByFixtureIdReturnsExisting() {
        long fixtureId = 8001L;
        MatchEntity existing = MatchEntity.builder()
                .organizationId(ORG).teamId(TEAM).sport(Sport.SOCCER)
                .kind(MatchKind.TOURNAMENT).status(MatchStatus.SCHEDULED).createdBy(ACTOR)
                .tournamentFixtureId(fixtureId).opponentName("相手FC").build();
        existing.setId(UUID.randomUUID());
        when(matchRepository
                .findFirstByOrganizationIdAndTeamIdAndTournamentFixtureIdOrderByKickoffAtDescIdDesc(
                        ORG, TEAM, fixtureId))
                .thenReturn(Optional.of(existing));

        var result = service.resolveByFixtureId(ORG, TEAM, ACTOR, fixtureId);

        // 認可委譲（一覧・入口④と同水準のメンバー以上）
        verify(matchAccessService).assertCanListTeamMatches(ACTOR, TEAM);
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(existing.getId());
        assertThat(result.get().getKind()).isEqualTo(MatchKind.TOURNAMENT);
        assertThat(result.get().getOpponentName()).isEqualTo("相手FC");
    }

    @Test
    @DisplayName("resolveByFixtureId: 既存が無ければ Optional.empty（FE は作成へ分岐）")
    void resolveByFixtureIdEmptyWhenNone() {
        long fixtureId = 8002L;
        when(matchRepository
                .findFirstByOrganizationIdAndTeamIdAndTournamentFixtureIdOrderByKickoffAtDescIdDesc(
                        ORG, TEAM, fixtureId))
                .thenReturn(Optional.empty());

        var result = service.resolveByFixtureId(ORG, TEAM, ACTOR, fixtureId);

        verify(matchAccessService).assertCanListTeamMatches(ACTOR, TEAM);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("resolveByFixtureId: 非メンバー（認可 403）ならリポジトリを呼ばずに伝播する")
    void resolveByFixtureIdNonMemberThrows() {
        doThrow(new BusinessException(MatchErrorCode.MATCH_010))
                .when(matchAccessService).assertCanListTeamMatches(ACTOR, TEAM);

        assertThatThrownBy(() -> service.resolveByFixtureId(ORG, TEAM, ACTOR, 8003L))
                .isInstanceOf(BusinessException.class);

        verify(matchRepository, never())
                .findFirstByOrganizationIdAndTeamIdAndTournamentFixtureIdOrderByKickoffAtDescIdDesc(
                        any(), any(), any());
    }

    @Test
    @DisplayName("getMatchOrThrow: 不在・テナント越境は 404（MATCH_001）")
    void getMatchNotFound404() {
        UUID other = UUID.randomUUID();
        when(matchRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(other, ORG))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getMatchOrThrow(other, ORG))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("見つかりません");
    }
}
