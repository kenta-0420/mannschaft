package com.mannschaft.app.reservation.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.timezone.TeamTimezoneResolver;
import com.mannschaft.app.reservation.ReservationDayOfWeek;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.dto.CreateRecurringBlockedTimeRequest;
import com.mannschaft.app.reservation.dto.RecurringBlockedTimeImpactResponse;
import com.mannschaft.app.reservation.dto.RecurringBlockedTimeResponse;
import com.mannschaft.app.reservation.dto.UpdateRecurringBlockedTimeRequest;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.entity.ReservationRecurringBlockedTimeEntity;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationRecurringBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationRecurringOverlapRow;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ReservationRecurringBlockedTimeService} の単体テスト（F03.4.5 §4 W2-2）。
 *
 * <p>受け入れ条件との対応: R1(正常作成)・R5(is_active切替)・R6(全日型欠落は呼び出し前にBean
 * Validationで400のためController契約テストの範疇・本テストはService層のバリデーション再検証のみ)・
 * R7(上限50)・R8(90日境界)・R9(409ガード)・R12(IDOR 404)・R15(1クエリでN+1化しない)。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationRecurringBlockedTimeService 単体テスト（F03.4.5 §4 W2-2）")
class ReservationRecurringBlockedTimeServiceTest {

    private static final Long TEAM_ID = 800L;
    private static final Long CREATED_BY = 900L;
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 10);

    @Mock
    private ReservationRecurringBlockedTimeRepository ruleRepository;
    @Mock
    private ReservationLineRepository lineRepository;
    @Mock
    private TeamTimezoneResolver teamTimezoneResolver;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private NameResolverService nameResolverService;
    @Mock
    private AuditLogService auditLogService;

    /** overlap 判定は純ロジックのため実インスタンスを注入（409ガードの実 throw を実検証）。 */
    private final ReservationUnavailabilityChecker unavailabilityChecker = new ReservationUnavailabilityChecker();

    private final Clock clock = Clock.fixed(
            TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    /**
     * F03.4.5 §6.2 W2-5（強行登録）で追加された依存。
     * impact もグループ兄弟行の展開のため枠を解決するようになったので、スタブ可能なフィールドで持つ。
     */
    @Mock
    private com.mannschaft.app.reservation.repository.ReservationSlotRepository slotRepository;

    private ReservationRecurringBlockedTimeService service;

    @BeforeEach
    void setUp() {
        // 本テストは force を使わない従来経路のみを見るため、slotService / eventPublisher は呼ばれない。
        service = new ReservationRecurringBlockedTimeService(
                ruleRepository, lineRepository, reservationRepository, unavailabilityChecker,
                nameResolverService, auditLogService, slotRepository,
                org.mockito.Mockito.mock(ReservationSlotService.class),
                org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class),
                clock, teamTimezoneResolver);
        given(teamTimezoneResolver.resolveZone(TEAM_ID)).willReturn(ZoneOffset.UTC);
    }

    private CreateRecurringBlockedTimeRequest createRequest(
            Long lineId, ReservationDayOfWeek dow, LocalTime start, LocalTime end, String reason, Boolean isPublic) {
        return new CreateRecurringBlockedTimeRequest(lineId, dow, start, end, reason, isPublic, null);
    }

    // ────────────────────────────────────────────────────────────
    // R-1: 正常作成
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R-1: TUE19-20 lineId=null reason=研修 isPublic=true で作成できる")
    void R1_正常作成() {
        given(ruleRepository.countByTeamId(TEAM_ID)).willReturn(0L);
        given(reservationRepository.findActiveReservationsInRangeForRecurringGuard(
                eq(TEAM_ID), any(), any(), any(), any())).willReturn(List.of());
        given(ruleRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        RecurringBlockedTimeResponse response = service.createRule(
                TEAM_ID,
                createRequest(null, ReservationDayOfWeek.TUE, LocalTime.of(19, 0), LocalTime.of(20, 0),
                        "研修", true),
                CREATED_BY);

        assertThat(response.getDayOfWeek()).isEqualTo("TUE");
        assertThat(response.getReason()).isEqualTo("研修");
        assertThat(response.getIsPublic()).isTrue();
        assertThat(response.getLineId()).isNull();

        ArgumentCaptor<ReservationRecurringBlockedTimeEntity> captor =
                ArgumentCaptor.forClass(ReservationRecurringBlockedTimeEntity.class);
        verify(ruleRepository).save(captor.capture());
        assertThat(captor.getValue().getTeamId()).isEqualTo(TEAM_ID);
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(CREATED_BY);
    }

    @Test
    @DisplayName("R-1: isPublic 未指定（null）は FALSE に正規化される")
    void R1_isPublic未指定はfalse正規化() {
        given(ruleRepository.countByTeamId(TEAM_ID)).willReturn(0L);
        given(reservationRepository.findActiveReservationsInRangeForRecurringGuard(
                eq(TEAM_ID), any(), any(), any(), any())).willReturn(List.of());
        given(ruleRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        RecurringBlockedTimeResponse response = service.createRule(
                TEAM_ID,
                createRequest(null, ReservationDayOfWeek.WED, LocalTime.of(9, 0), LocalTime.of(12, 0),
                        "スクール", null),
                CREATED_BY);

        assertThat(response.getIsPublic()).isFalse();
    }

    @Test
    @DisplayName("lineId 指定時は当該チームの active ラインであることを検証（不正は 400=LINE_NOT_FOUND）")
    void lineId不正は400() {
        given(ruleRepository.countByTeamId(TEAM_ID)).willReturn(0L);
        given(lineRepository.findByIdAndTeamId(999L, TEAM_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.createRule(
                TEAM_ID,
                createRequest(999L, ReservationDayOfWeek.MON, LocalTime.of(10, 0), LocalTime.of(11, 0),
                        "研修", true),
                CREATED_BY))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.LINE_NOT_FOUND);
    }

    // ────────────────────────────────────────────────────────────
    // R-7: 上限50行
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R-7: 51件目の作成は400（RECURRING_BLOCKED_TIME_LIMIT_EXCEEDED）で拒否・行は増えない")
    void R7_上限50行超過は400() {
        given(ruleRepository.countByTeamId(TEAM_ID)).willReturn(50L);

        assertThatThrownBy(() -> service.createRule(
                TEAM_ID,
                createRequest(null, ReservationDayOfWeek.MON, LocalTime.of(10, 0), LocalTime.of(11, 0),
                        "研修", true),
                CREATED_BY))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.RECURRING_BLOCKED_TIME_LIMIT_EXCEEDED);

        verify(ruleRepository, never()).save(any());
    }

    // ────────────────────────────────────────────────────────────
    // R-9: 409 ガード（90日以内 active 予約あり）／ R-8: 91日以降のみは通る
    // ────────────────────────────────────────────────────────────

    private ReservationRecurringOverlapRow overlapRow(LocalDate slotDate, LocalTime start, LocalTime end) {
        return new ReservationRecurringOverlapRow(
                1L, 500L, 2000L, slotDate, null, null, start, end, ReservationStatus.CONFIRMED);
    }

    /**
     * {@code overlapRow} と対応する実エンティティを stub する（W2-5 検分 MUST③ の追随）。
     *
     * <p>impact はグループ兄弟行まで展開するため、projection 行だけでなく<b>実エンティティと枠</b>を
     * 解決するようになった（強行キャンセルと同一の集合解決を共有し「impact の件数と force の件数が
     * ずれる」事故を構造的に防ぐため）。アサーション（件数・氏名・枠日付）は一切変えていない。</p>
     */
    private void givenOverlapEntities(LocalDate slotDate, LocalTime start, LocalTime end) {
        ReservationEntity reservation = ReservationEntity.builder()
                .teamId(TEAM_ID).userId(500L).lineId(1L)
                .reservationSlotId(2000L).status(ReservationStatus.CONFIRMED)
                .build();
        setTestId(reservation, 1L);
        ReservationSlotEntity slot = ReservationSlotEntity.builder()
                .teamId(TEAM_ID).title("枠")
                .slotDate(slotDate).startTime(start).endTime(end)
                .capacity(1).build();
        setTestId(slot, 2000L);
        given(reservationRepository.findAllById(any())).willReturn(List.of(reservation));
        given(slotRepository.findAllById(any())).willReturn(List.of(slot));
    }

    /** ID は永続化で採番されるため、テストでは reflection で埋める（Entity に setter を生やさない）。 */
    private static void setTestId(Object entity, Long id) {
        try {
            Class<?> c = entity.getClass();
            java.lang.reflect.Field f = null;
            while (c != null && f == null) {
                try {
                    f = c.getDeclaredField("id");
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            if (f == null) {
                throw new NoSuchFieldException("id");
            }
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("R-9: 90日以内にoverlapするactive予約があればPOSTは409（RESERVATION_027）で拒否・行は増えない")
    void R9_90日以内overlapは409() {
        given(ruleRepository.countByTeamId(TEAM_ID)).willReturn(0L);
        LocalDate matchDay = TODAY.plusDays(30);
        // matchDay の曜日と同じ曜日のルールを提案する。
        ReservationDayOfWeek dow = ReservationDayOfWeek.from(matchDay);
        given(reservationRepository.findActiveReservationsInRangeForRecurringGuard(
                eq(TEAM_ID), eq(TODAY), eq(TODAY.plusDays(90)), any(), any()))
                .willReturn(List.of(overlapRow(matchDay, LocalTime.of(19, 0), LocalTime.of(20, 0))));

        assertThatThrownBy(() -> service.createRule(
                TEAM_ID,
                createRequest(null, dow, LocalTime.of(19, 0), LocalTime.of(20, 0), "研修", true),
                CREATED_BY))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.UNAVAILABILITY_HAS_ACTIVE_RESERVATIONS);

        verify(ruleRepository, never()).save(any());
    }

    @Test
    @DisplayName("R-8: horizon(90日)を超える予約しかない場合はPOSTが通る（境界=today+90のみ判定対象）")
    void R8_91日以降のみは通る() {
        given(ruleRepository.countByTeamId(TEAM_ID)).willReturn(0L);
        // findActiveReservationsInRangeForRecurringGuard は [today, today+90] のみを引く前提のため、
        // 91日以降の予約はそもそも SQL レンジ条件で候補に現れない（=空リストが返る）。
        given(reservationRepository.findActiveReservationsInRangeForRecurringGuard(
                eq(TEAM_ID), eq(TODAY), eq(TODAY.plusDays(90)), any(), any()))
                .willReturn(List.of());
        given(ruleRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        RecurringBlockedTimeResponse response = service.createRule(
                TEAM_ID,
                createRequest(null, ReservationDayOfWeek.MON, LocalTime.of(19, 0), LocalTime.of(20, 0),
                        "研修", true),
                CREATED_BY);

        assertThat(response).isNotNull();
        verify(ruleRepository).save(any());
        // horizon の境界値そのもの（today+90）で問い合わせていることを検証する。
        verify(reservationRepository).findActiveReservationsInRangeForRecurringGuard(
                eq(TEAM_ID), eq(TODAY), eq(TODAY.plusDays(90)), any(), any());
    }

    // ────────────────────────────────────────────────────────────
    // R-15: ホットパスは1クエリ（N+1化しない）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R-15: 409ガードは reservationRepository への問い合わせ1回のみ（N+1化しない）")
    void R15_ガードは1クエリ() {
        given(ruleRepository.countByTeamId(TEAM_ID)).willReturn(0L);
        given(reservationRepository.findActiveReservationsInRangeForRecurringGuard(
                eq(TEAM_ID), any(), any(), any(), any())).willReturn(List.of());
        given(ruleRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.createRule(
                TEAM_ID,
                createRequest(null, ReservationDayOfWeek.MON, LocalTime.of(10, 0), LocalTime.of(11, 0),
                        "研修", true),
                CREATED_BY);

        verify(reservationRepository, times(1)).findActiveReservationsInRangeForRecurringGuard(
                any(), any(), any(), any(), any());
    }

    // ────────────────────────────────────────────────────────────
    // R-5: is_active=FALSE 化で即予約可（判定対象外）
    // ────────────────────────────────────────────────────────────

    private ReservationRecurringBlockedTimeEntity existingRule(UUID id) {
        return ReservationRecurringBlockedTimeEntity.builder()
                .id(id).teamId(TEAM_ID).dayOfWeek(ReservationDayOfWeek.TUE)
                .startTime(LocalTime.of(19, 0)).endTime(LocalTime.of(20, 0))
                .reason("研修").isPublic(true).isActive(true).build();
    }

    @Test
    @DisplayName("R-5: isActive=false へ更新すると判定対象外になる（エンティティの isActiveRule が false を返す）")
    void R5_無効化で判定対象外() {
        UUID ruleId = UUID.randomUUID();
        ReservationRecurringBlockedTimeEntity entity = existingRule(ruleId);
        given(ruleRepository.findByIdAndTeamId(ruleId, TEAM_ID)).willReturn(Optional.of(entity));
        given(reservationRepository.findActiveReservationsInRangeForRecurringGuard(
                eq(TEAM_ID), any(), any(), any(), any())).willReturn(List.of());
        given(ruleRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        UpdateRecurringBlockedTimeRequest request =
                new UpdateRecurringBlockedTimeRequest(null, null, null, null, null, null, null, false, null);
        RecurringBlockedTimeResponse response = service.updateRule(TEAM_ID, ruleId, request, CREATED_BY);

        assertThat(response.getIsActive()).isFalse();
        assertThat(entity.isActiveRule()).isFalse();
    }

    // ────────────────────────────────────────────────────────────
    // R-12: IDOR — 他チームの ruleId は 404 で秘匿（GET/PATCH/DELETE 統一）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R-12: 他チームのruleIdでPATCHすると404（RECURRING_BLOCKED_TIME_NOT_FOUND）")
    void R12_他チームPATCHは404() {
        UUID ruleId = UUID.randomUUID();
        given(ruleRepository.findByIdAndTeamId(ruleId, TEAM_ID)).willReturn(Optional.empty());

        UpdateRecurringBlockedTimeRequest request =
                new UpdateRecurringBlockedTimeRequest(null, null, null, null, null, "変更後", null, null, null);

        assertThatThrownBy(() -> service.updateRule(TEAM_ID, ruleId, request, CREATED_BY))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.RECURRING_BLOCKED_TIME_NOT_FOUND);
    }

    @Test
    @DisplayName("R-12: 他チームのruleIdでDELETEすると404")
    void R12_他チームDELETEは404() {
        UUID ruleId = UUID.randomUUID();
        given(ruleRepository.findByIdAndTeamId(ruleId, TEAM_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteRule(TEAM_ID, ruleId, CREATED_BY))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.RECURRING_BLOCKED_TIME_NOT_FOUND);

        verify(ruleRepository, never()).delete(any());
    }

    // ────────────────────────────────────────────────────────────
    // impact（副作用ゼロ）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("impact: overlapする active 予約が0件なら affectedCount=0・reservations=空")
    void impact_該当なし() {
        given(reservationRepository.findActiveReservationsInRangeForRecurringGuard(
                eq(TEAM_ID), any(), any(), any(), any())).willReturn(List.of());

        RecurringBlockedTimeImpactResponse response =
                service.getImpact(TEAM_ID, ReservationDayOfWeek.MON, LocalTime.of(10, 0), LocalTime.of(11, 0), null);

        assertThat(response.getAffectedCount()).isZero();
        assertThat(response.getReservations()).isEmpty();
    }

    @Test
    @DisplayName("impact: overlapする active 予約があれば件数・氏名込み一覧を返す（副作用ゼロ・save/deleteは呼ばれない）")
    void impact_該当あり() {
        LocalDate matchDay = TODAY.plusDays(10);
        ReservationDayOfWeek dow = ReservationDayOfWeek.from(matchDay);
        given(reservationRepository.findActiveReservationsInRangeForRecurringGuard(
                eq(TEAM_ID), any(), any(), any(), any()))
                .willReturn(List.of(overlapRow(matchDay, LocalTime.of(10, 0), LocalTime.of(11, 0))));
        givenOverlapEntities(matchDay, LocalTime.of(10, 0), LocalTime.of(11, 0));
        given(nameResolverService.resolveUserFullNames(any()))
                .willReturn(java.util.Map.of(500L, "山田 太郎"));

        RecurringBlockedTimeImpactResponse response =
                service.getImpact(TEAM_ID, dow, LocalTime.of(10, 0), LocalTime.of(11, 0), null);

        assertThat(response.getAffectedCount()).isEqualTo(1);
        assertThat(response.getReservations().get(0).userName()).isEqualTo("山田 太郎");
        assertThat(response.getReservations().get(0).slotDate()).isEqualTo(matchDay);
        verify(ruleRepository, never()).save(any());
        verify(ruleRepository, never()).delete(any());
    }

    // ────────────────────────────────────────────────────────────
    // 一覧
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("一覧は曜日→開始時刻順に整列される")
    void listRules_曜日昇順整列() {
        ReservationRecurringBlockedTimeEntity wed = ReservationRecurringBlockedTimeEntity.builder()
                .teamId(TEAM_ID).dayOfWeek(ReservationDayOfWeek.WED)
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(10, 0))
                .reason("スクール").isPublic(true).isActive(true).build();
        ReservationRecurringBlockedTimeEntity monLate = ReservationRecurringBlockedTimeEntity.builder()
                .teamId(TEAM_ID).dayOfWeek(ReservationDayOfWeek.MON)
                .startTime(LocalTime.of(19, 0)).endTime(LocalTime.of(20, 0))
                .reason("研修B").isPublic(false).isActive(true).build();
        ReservationRecurringBlockedTimeEntity monEarly = ReservationRecurringBlockedTimeEntity.builder()
                .teamId(TEAM_ID).dayOfWeek(ReservationDayOfWeek.MON)
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(10, 0))
                .reason("研修A").isPublic(false).isActive(true).build();
        given(ruleRepository.findByTeamId(TEAM_ID)).willReturn(List.of(wed, monLate, monEarly));

        List<RecurringBlockedTimeResponse> list = service.listRules(TEAM_ID);

        assertThat(list).extracting(RecurringBlockedTimeResponse::getReason)
                .containsExactly("研修A", "研修B", "スクール");
    }
    @Test
    @DisplayName("endsNextDay ruleは翌日00時台をimpactへ含める")
    void impactEndsNextDayIncludesNextDayCell() {
        LocalDate monday = TODAY.plusDays(10);
        LocalDate tuesday = monday.plusDays(1);
        ReservationRecurringOverlapRow row = new ReservationRecurringOverlapRow(
                1L, 500L, 2000L, tuesday, tuesday, null, null,
                LocalTime.of(0, 0), LocalTime.of(1, 0), ReservationStatus.CONFIRMED);
        given(reservationRepository.findActiveReservationsInRangeForRecurringGuard(
                eq(TEAM_ID), any(), any(), any(), any())).willReturn(List.of(row));
        givenOverlapEntities(tuesday, LocalTime.of(0, 0), LocalTime.of(1, 0));
        given(nameResolverService.resolveUserFullNames(any())).willReturn(java.util.Map.of(500L, "user"));

        RecurringBlockedTimeImpactResponse response = service.getImpact(
                TEAM_ID, ReservationDayOfWeek.from(monday), LocalTime.of(23, 0), LocalTime.of(1, 0), null);

        assertThat(response.getAffectedCount()).isEqualTo(1);
    }
}
