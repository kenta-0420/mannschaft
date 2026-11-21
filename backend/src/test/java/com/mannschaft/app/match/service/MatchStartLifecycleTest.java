package com.mannschaft.app.match.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.match.MatchErrorCode;
import com.mannschaft.app.match.MatchRecordingStartedEvent;
import com.mannschaft.app.match.domain.MatchEventType;
import com.mannschaft.app.match.domain.MatchStatus;
import com.mannschaft.app.match.domain.PeriodType;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.entity.MatchEventEntity;
import com.mannschaft.app.match.live.MatchLiveUpdateEvent;
import com.mannschaft.app.match.live.MatchLiveUpdateType;
import com.mannschaft.app.match.repository.MatchEventRepository;
import com.mannschaft.app.match.repository.MatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 公開record経路から、開始の永続状態・認可・監査・配信の契約を検査する。 */
class MatchStartLifecycleTest {
    private static final long ORG = 50L;
    private static final long TEAM = 100L;
    private static final long ACTOR = 2L;
    private final MatchRepository matches = mock(MatchRepository.class);
    private final MatchEventRepository events = mock(MatchEventRepository.class);
    private final MatchAccessService access = mock(MatchAccessService.class);
    private final PlayingTimeCalculationService calculation = mock(PlayingTimeCalculationService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private MatchEventService service;
    private MatchEntity match;

    @BeforeEach
    void setUp() {
        match = MatchEntity.builder().id(UUID.randomUUID()).organizationId(ORG).teamId(TEAM)
                .sport(Sport.SOCCER).status(MatchStatus.SCHEDULED).hasScorekeeper(true)
                .scorekeeperUserId(ACTOR).createdBy(1L).build();
        when(matches.findByIdAndOrganizationIdAndDeletedAtIsNull(match.getId(), ORG))
                .thenReturn(Optional.of(match));
        when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(matches.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        MatchService matchService = new MatchService(matches, null, null, access, calculation, publisher, audit);
        service = new MatchEventService(events, matchService, access, calculation, publisher);
    }

    private MatchEventService.EventCommand command(MatchEventType type) {
        return MatchEventService.EventCommand.builder().eventType(type).period(PeriodType.FIRST_HALF)
                .minute(0).teamSide(TeamSide.HOME).recordedByTeamId(TEAM).build();
    }

    @Test
    @DisplayName("前半開始を受理すると公開record経路で進行中になり、監査イベントとSTATUS_CHANGEDが一度だけ発行される")
    void acceptedStartChangesStatusOnce() {
        service.record(match.getId(), ORG, ACTOR, command(MatchEventType.PERIOD_START));
        assertThat(match.getStatus()).isEqualTo(MatchStatus.IN_PROGRESS);
        verify(matches).save(match);
        verify(access).assertCanRecordTimeline(ACTOR, match);
        verify(access, never()).assertCanEditMeta(any(), any());
        // 監査は独立したAFTER_COMMITリスナーが担い、業務TXから直接呼ばない。
        verifyNoInteractions(audit);
        ArgumentCaptor<Object> updates = ArgumentCaptor.forClass(Object.class);
        verify(publisher, times(3)).publishEvent(updates.capture());
        assertThat(updates.getAllValues()).filteredOn(e -> e instanceof MatchLiveUpdateEvent live
                && live.getType() == MatchLiveUpdateType.STATUS_CHANGED).singleElement()
                .satisfies(e -> assertThat(((MatchLiveUpdateEvent) e).getStatus()).isEqualTo(MatchStatus.IN_PROGRESS));
        assertThat(updates.getAllValues()).filteredOn(e -> e instanceof MatchRecordingStartedEvent).singleElement()
                .satisfies(e -> assertThat((MatchRecordingStartedEvent) e).isEqualTo(new MatchRecordingStartedEvent(
                        match.getId(), TEAM, ORG, ACTOR, MatchStatus.SCHEDULED, MatchStatus.IN_PROGRESS)));
        service.record(match.getId(), ORG, ACTOR, command(MatchEventType.PERIOD_START));
        verify(matches).save(match);
        verify(publisher, times(4)).publishEvent(any(Object.class));
    }

    @ParameterizedTest
    @EnumSource(value = MatchStatus.class, names = "SCHEDULED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("予定以外の開始記録で既存statusを巻き戻さずstatus監査を増やさない")
    void startPreservesOtherStatuses(MatchStatus status) {
        match.setStatus(status);
        service.record(match.getId(), ORG, ACTOR, command(MatchEventType.PERIOD_START));
        assertThat(match.getStatus()).isEqualTo(status);
        verify(matches, never()).save(any());
        verifyNoInteractions(audit);
        verify(publisher).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("通常イベントや最終終了だけでstatusを変更しない")
    void nonStartDoesNotChangeStatus() {
        service.record(match.getId(), ORG, ACTOR, command(MatchEventType.PERIOD_END));
        assertThat(match.getStatus()).isEqualTo(MatchStatus.SCHEDULED);
        verifyNoInteractions(audit);
        verify(matches, never()).save(any());
    }

    @Test
    @DisplayName("記録権限の拒否ではeventもstatusも保存しない")
    void rejectedActorDoesNotStart() {
        doThrow(new BusinessException(MatchErrorCode.MATCH_010)).when(access).assertCanRecordTimeline(ACTOR, match);
        assertThatThrownBy(() -> service.record(match.getId(), ORG, ACTOR, command(MatchEventType.PERIOD_START)))
                .isInstanceOf(BusinessException.class);
        assertThat(match.getStatus()).isEqualTo(MatchStatus.SCHEDULED);
        verify(events, never()).save(any());
        verifyNoInteractions(audit, publisher);
    }

    @Test
    @DisplayName("無効な開始入力ではeventもstatusも保存しない")
    void invalidStartDoesNotStart() {
        var invalid = MatchEventService.EventCommand.builder().eventType(MatchEventType.PERIOD_START)
                .minute(-1).teamSide(TeamSide.HOME).recordedByTeamId(TEAM).build();
        assertThatThrownBy(() -> service.record(match.getId(), ORG, ACTOR, invalid))
                .isInstanceOf(BusinessException.class);
        assertThat(match.getStatus()).isEqualTo(MatchStatus.SCHEDULED);
        verify(events, never()).save(any());
        verifyNoInteractions(audit, publisher);
    }

    @Test
    @DisplayName("既存イベントのSTARTへの訂正ではstatusを変更しない")
    void editingHistoryDoesNotStart() {
        MatchEventEntity existing = MatchEventEntity.builder().id(UUID.randomUUID()).matchId(match.getId())
                .eventType(MatchEventType.GOAL).recordedByTeamId(TEAM).teamSide(TeamSide.HOME).build();
        when(events.findById(existing.getId())).thenReturn(Optional.of(existing));
        service.update(match.getId(), existing.getId(), ORG, ACTOR, command(MatchEventType.PERIOD_START));
        assertThat(match.getStatus()).isEqualTo(MatchStatus.SCHEDULED);
        verifyNoInteractions(audit);
        verify(matches, never()).save(any());
    }
}
