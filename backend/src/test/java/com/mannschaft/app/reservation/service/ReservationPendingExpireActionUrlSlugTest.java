package com.mannschaft.app.reservation.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.timezone.TeamTimezoneResolver;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationPolicyRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository.PendingExpireCandidate;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.service.ReservationPendingExpireProgressService.RunState;
import com.mannschaft.app.reservation.service.ReservationPendingExpireService.PendingExpireUnit;
import com.mannschaft.app.reservation.service.ReservationPendingExpireService.ScanEntry;
import com.mannschaft.app.team.service.TeamService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * CMP-261030-0648 部隊C: 仮押さえ失効通知の actionUrl を slug で組み立てる（AC-21a〜f）。
 *
 * <p>FE のチームルートは slug しか受けないため、数値 teamId の URL では予約画面へ着けない。
 * slug は非TXの抽出段（{@code findScanPlan}）で一括取得して Unit に運び、unit TX
 * （{@code expireUnit}・REQUIRES_NEW）の中では {@link TeamService} を呼ばない。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("仮押さえ失効通知の遷移先 slug 化（CMP-261030-0648 AC-21）")
class ReservationPendingExpireActionUrlSlugTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    @Mock private ReservationRepository reservationRepository;
    @Mock private ReservationSlotRepository slotRepository;
    @Mock private ReservationSlotService slotService;
    @Mock private NotificationHelper notificationHelper;
    @Mock private UserLocaleCache userLocaleCache;
    @Mock private MessageSource messageSource;
    @Mock private Clock clock;
    @Mock private ReservationPolicyRepository policyRepository;
    @Mock private ReservationPendingExpireProgressService progress;
    @Mock private TeamTimezoneResolver timezoneResolver;
    @Mock private EntityManager entityManager;
    @Mock private TeamService teamService;

    @InjectMocks
    private ReservationPendingExpireService service;

    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> logs;

    /** 抽出クエリの射影を素直に満たす候補（bookedExpired=true で即 eligible）。 */
    private record Candidate(ReservationEntity getPrimary) implements PendingExpireCandidate {
        @Override public LocalDate getSlotDate() { return null; }
        @Override public LocalDate getEndDate() { return null; }
        @Override public LocalTime getEndTime() { return null; }
        @Override public Boolean getEnabled() { return true; }
        @Override public Boolean getBookedExpired() { return true; }
    }

    @BeforeEach
    void setUp() {
        given(clock.instant()).willReturn(Instant.parse("2026-11-10T03:00:00Z"));
        given(userLocaleCache.getLocale(any())).willReturn("ja");
        given(reservationRepository.pendingExpireElapsedHours(any(), any())).willReturn(100L);
        given(policyRepository.findPendingExpirePolicyForUpdate(anyLong())).willReturn(Optional.empty());
        serviceLogger = (Logger) LoggerFactory.getLogger(ReservationPendingExpireService.class);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logs);
    }

    private ReservationEntity reservation(long id, long teamId) {
        return ReservationEntity.builder()
                .id(id)
                .reservationSlotId(id * 10)
                .lineId(1L)
                .teamId(teamId)
                .userId(9000L + id)
                .status(ReservationStatus.PENDING)
                .isGroupPrimary(true)
                .bookedAt(LocalDateTime.of(2026, 11, 1, 12, 0))
                .build();
    }

    private ReservationSlotEntity slot(long id, long teamId) {
        return ReservationSlotEntity.builder()
                .id(id)
                .teamId(teamId)
                .title("枠" + id)
                .slotDate(LocalDate.of(2026, 11, 20))
                .endDate(LocalDate.of(2026, 11, 20))
                .startTime(LocalTime.of(10, 0))
                .endTime(LocalTime.of(11, 0))
                .build();
    }

    /** 抽出候補を用意し、unit TX での最新行再読・枠ロックも同じ行を返すようにする。 */
    private void stubCandidates(ReservationEntity... rows) {
        var candidates = java.util.Arrays.stream(rows).map(row -> (PendingExpireCandidate) new Candidate(row)).toList();
        given(reservationRepository.findPendingPrimaryCandidates(
                eq(ReservationStatus.PENDING), anyLong(), anyLong(), any(), anyInt(), any(Pageable.class)))
                .willReturn(candidates);
        for (var row : rows) {
            given(reservationRepository.findPendingExpirePrimaryForUpdate(row.getId())).willReturn(Optional.of(row));
        }
        given(slotRepository.findPendingExpireSlotsForUpdate(anyCollection())).willAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return java.util.Arrays.stream(rows)
                    .filter(row -> ids.contains(row.getReservationSlotId()))
                    .map(row -> slot(row.getReservationSlotId(), row.getTeamId())).toList();
        });
    }

    private List<PendingExpireUnit> scanUnits() {
        var plan = service.findScanPlan(new RunState(1, 1000, 0, List.of()));
        return plan.entries().stream().map(ScanEntry::unit).filter(Objects::nonNull).toList();
    }

    /** バッチと同じく withProgress() で作り直した Unit を実行し、送った actionUrl を teamId 順に返す。 */
    private List<String> expireAndCaptureActionUrls(List<PendingExpireUnit> units) {
        long prefix = 0;
        for (var unit : units) {
            prefix = unit.primary().getId();
            service.expireUnit(unit.withProgress(1, prefix, ZONE));
        }
        ArgumentCaptor<String> actionUrl = ArgumentCaptor.forClass(String.class);
        verify(notificationHelper, times(units.size())).notifyAfterCommit(
                any(), eq(ReservationPendingExpireService.NOTIFICATION_TYPE), any(), any(),
                eq(ReservationPendingExpireService.SOURCE_TYPE), any(), eq(NotificationScopeType.TEAM), any(),
                actionUrl.capture(), isNull());
        return actionUrl.getAllValues();
    }

    @Test
    @DisplayName("AC-21a/21e: withProgress() で作り直した Unit でも actionUrl は /teams/{slug}/reservations")
    void 失効通知のactionUrlはslug() {
        stubCandidates(reservation(1, 10));
        given(teamService.getSlugsByIds(anyCollection())).willReturn(Map.of(10L, "alpha-fc"));

        var urls = expireAndCaptureActionUrls(scanUnits());

        assertThat(urls).containsExactly("/teams/alpha-fc/reservations");
    }

    @Test
    @DisplayName("AC-21b: slug が引けないチームは数値 URL を作らず /notifications")
    void slug欠落は通知一覧へ() {
        stubCandidates(reservation(1, 10), reservation(2, 20));
        given(teamService.getSlugsByIds(anyCollection())).willReturn(Map.of(10L, "alpha-fc"));

        var urls = expireAndCaptureActionUrls(scanUnits());

        assertThat(urls).containsExactly("/teams/alpha-fc/reservations", "/notifications");
    }

    @Test
    @DisplayName("AC-21c: slug 取得が例外でも失効は続き /notifications で通知し、WARN を残す")
    void slug取得例外でも失効は続く() {
        stubCandidates(reservation(1, 10));
        given(teamService.getSlugsByIds(anyCollection())).willThrow(new IllegalStateException("db down"));

        var units = scanUnits();
        assertThat(units).as("slug の失敗で失効対象を落とさない").hasSize(1);
        var urls = expireAndCaptureActionUrls(units);

        assertThat(urls).containsExactly("/notifications");
        verify(reservationRepository).saveAll(any());
        verify(teamService).getSlugsByIds(anyCollection());
        assertThat(logs.list)
                .as("slug 取得の失敗は WARN で残す")
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage()).contains("slug");
                });
    }

    @Test
    @DisplayName("AC-21d: 対象0件なら slug を引かない")
    void 対象0件ならslugを引かない() {
        given(reservationRepository.findPendingPrimaryCandidates(
                eq(ReservationStatus.PENDING), anyLong(), anyLong(), any(), anyInt(), any(Pageable.class)))
                .willReturn(List.of());

        assertThat(scanUnits()).isEmpty();

        verify(teamService, never()).getSlugsByIds(anyCollection());
        verify(teamService, never()).getSlugById(any());
    }

    @Test
    @DisplayName("AC-21d: 複数 Unit・複数チームでも slug は一括取得1回")
    @SuppressWarnings("unchecked")
    void 複数チームでも一括取得1回() {
        stubCandidates(reservation(1, 10), reservation(2, 20), reservation(3, 10));
        given(teamService.getSlugsByIds(anyCollection())).willReturn(Map.of(10L, "alpha-fc", 20L, "beta-fc"));

        var urls = expireAndCaptureActionUrls(scanUnits());

        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(teamService, times(1)).getSlugsByIds(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(10L, 20L);
        verify(teamService, never()).getSlugById(any());
        assertThat(urls).containsExactly(
                "/teams/alpha-fc/reservations", "/teams/beta-fc/reservations", "/teams/alpha-fc/reservations");
    }

    @Test
    @DisplayName("AC-21f: TeamService は抽出段でだけ呼び、unit TX（expireUnit）の中では呼ばない")
    void unitTXの中ではTeamServiceを呼ばない() {
        stubCandidates(reservation(1, 10));
        given(teamService.getSlugsByIds(anyCollection())).willReturn(Map.of(10L, "alpha-fc"));

        var units = scanUnits();
        verify(teamService).getSlugsByIds(anyCollection());

        expireAndCaptureActionUrls(units);

        verifyNoMoreInteractions(teamService);
    }
}
