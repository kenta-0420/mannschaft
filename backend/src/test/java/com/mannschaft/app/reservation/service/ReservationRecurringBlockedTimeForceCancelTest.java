package com.mannschaft.app.reservation.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.reservation.CancelledBy;
import com.mannschaft.app.reservation.ReservationDayOfWeek;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.SlotStatus;
import com.mannschaft.app.reservation.dto.CreateRecurringBlockedTimeRequest;
import com.mannschaft.app.reservation.dto.RecurringBlockedTimeResponse;
import com.mannschaft.app.reservation.dto.UpdateRecurringBlockedTimeRequest;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationRecurringBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.event.ReservationForceCancelledByBlockEvent;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationRecurringBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationRecurringOverlapRow;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 定期予約不可枠の<b>強行登録</b>テスト（F03.4.5 §6.2 W2-5・殿の裁定 2026-07-30・AC-5-17）。
 *
 * <h2>なぜこの機能が必要か（テストが守っている不変条件）</h2>
 * <p>§4.3 の 409 ガードは「今日から 90 日先までに active 予約があれば拒否」する。一方 §6.2 の定期予約は
 * 最大 12 週 = 約 84 日分の予約を並べる。したがって<b>会員 1 人が定期予約を入れるだけで、管理者は
 * 「毎週火曜19時は研修」を恒久的に登録できなくなる</b>。この構造的破綻を
 * {@code forceCancelConflicting} で根治する（「409 のまま運用で回避」は不可という裁定）。</p>
 *
 * <p>検証項目:</p>
 * <ul>
 *   <li>force 未指定/false は<b>従来どおり 409</b>（挙動不変・回帰なし）</li>
 *   <li>force=true は衝突予約を CANCELLED（{@code cancelledBy=ADMIN}・定型文）にしてから登録する</li>
 *   <li>枠復帰は {@code decrementAndReopen} を経由する（§6.1 キャンセル待ち通知の唯一の統合点）</li>
 *   <li>申込者へ通知イベントが行数ぶん発行される（黙って消さない）</li>
 *   <li>グループ予約は兄弟行まで一括キャンセル（部分キャンセルを作らない）</li>
 *   <li>PATCH でも同じ挙動</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("定期予約不可枠 強行登録テスト（F03.4.5 §6.2 / AC-5-17）")
class ReservationRecurringBlockedTimeForceCancelTest {

    private static final Long TEAM_ID = 7101L;
    private static final Long ADMIN_ID = 7102L;
    private static final Long APPLICANT_A = 7103L;
    private static final Long APPLICANT_B = 7104L;

    /** 2026-06-02 は火曜。 */
    private static final LocalDate TUESDAY = LocalDate.of(2026, 6, 2);
    private static final LocalTime START = LocalTime.of(19, 0);
    private static final LocalTime END = LocalTime.of(20, 0);

    private static final Clock FIXED_CLOCK =
            Clock.fixed(LocalDate.of(2026, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneId.of("UTC"));

    @Mock
    private ReservationRecurringBlockedTimeRepository ruleRepository;
    @Mock
    private ReservationLineRepository lineRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private NameResolverService nameResolverService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private ReservationSlotRepository slotRepository;
    @Mock
    private ReservationSlotService slotService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ReservationRecurringBlockedTimeService service;

    private final java.util.Map<Long, ReservationEntity> reservations = new java.util.LinkedHashMap<>();
    private final java.util.Map<Long, ReservationSlotEntity> slots = new java.util.LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        service = new ReservationRecurringBlockedTimeService(
                ruleRepository, lineRepository, reservationRepository,
                new ReservationUnavailabilityChecker(), nameResolverService, auditLogService,
                slotRepository, slotService, eventPublisher, FIXED_CLOCK);

        given(ruleRepository.countByTeamId(anyLong())).willReturn(0L);
        given(ruleRepository.save(any())).willAnswer(inv -> {
            ReservationRecurringBlockedTimeEntity e = inv.getArgument(0);
            setField(e, "id", UUID.fromString("018f0000-0000-7000-8000-0000000000aa"));
            return e;
        });
        given(nameResolverService.resolveUserFullNames(anyCollection())).willReturn(java.util.Map.of());
        given(reservationRepository.save(any(ReservationEntity.class))).willAnswer(inv -> inv.getArgument(0));
        given(reservationRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));
        given(reservationRepository.findAllById(anyIterable())).willAnswer(inv -> {
            List<ReservationEntity> out = new ArrayList<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) {
                ReservationEntity r = reservations.get(id);
                if (r != null) {
                    out.add(r);
                }
            }
            return out;
        });
        given(slotRepository.findAllById(anyIterable())).willAnswer(inv -> {
            List<ReservationSlotEntity> out = new ArrayList<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) {
                ReservationSlotEntity s = slots.get(id);
                if (s != null) {
                    out.add(s);
                }
            }
            return out;
        });
    }

    private ReservationSlotEntity seedSlot(Long slotId, LocalDate date) {
        ReservationSlotEntity s = ReservationSlotEntity.builder()
                .teamId(TEAM_ID).lineId(null).title("レッスン")
                .slotDate(date).startTime(START).endTime(END)
                .capacity(1).bookedCount(1).slotStatus(SlotStatus.FULL)
                .build();
        setField(s, "id", slotId);
        slots.put(slotId, s);
        return s;
    }

    private ReservationEntity seedReservation(
            Long id, Long userId, Long slotId, UUID groupId, boolean primary, ReservationStatus status) {
        ReservationEntity r = ReservationEntity.builder()
                .teamId(TEAM_ID).userId(userId).lineId(1L)
                .reservationSlotId(slotId).status(status)
                .groupId(groupId).isGroupPrimary(primary)
                .build();
        setField(r, "id", id);
        reservations.put(id, r);
        return r;
    }

    /** 90日 horizon の候補行として返す projection を作る。 */
    private ReservationRecurringOverlapRow row(Long reservationId, Long userId, Long slotId, LocalDate date) {
        return new ReservationRecurringOverlapRow(
                reservationId, userId, slotId, date, null, null, START, END, ReservationStatus.CONFIRMED);
    }

    private void givenOverlapping(ReservationRecurringOverlapRow... rows) {
        given(reservationRepository.findActiveReservationsInRangeForRecurringGuard(
                anyLong(), any(), any(), any(), any())).willReturn(List.of(rows));
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Class<?> c = target.getClass();
            java.lang.reflect.Field f = null;
            while (c != null && f == null) {
                try {
                    f = c.getDeclaredField(name);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            if (f == null) {
                throw new NoSuchFieldException(name);
            }
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private CreateRecurringBlockedTimeRequest createRequest(Boolean force) {
        return new CreateRecurringBlockedTimeRequest(
                null, ReservationDayOfWeek.TUE, START, END, "研修", true, force);
    }

    // ────────────────────────────────────────────────────────────
    // 従来経路の回帰（force なし = 409 のまま）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-5-17: forceCancelConflicting 未指定は従来どおり 409（RESERVATION_027）で拒否する")
    void force未指定は従来どおり409() {
        seedSlot(9101L, TUESDAY);
        seedReservation(201L, APPLICANT_A, 9101L, null, true, ReservationStatus.CONFIRMED);
        givenOverlapping(row(201L, APPLICANT_A, 9101L, TUESDAY));

        assertThatThrownBy(() -> service.createRule(TEAM_ID, createRequest(null), ADMIN_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.UNAVAILABILITY_HAS_ACTIVE_RESERVATIONS);

        assertThat(reservations.get(201L).getStatus())
                .as("既存予約は絶対に触らない（従来挙動の完全維持）")
                .isEqualTo(ReservationStatus.CONFIRMED);
        verify(ruleRepository, never()).save(any());
        verify(slotService, never()).decrementAndReopen(any());
        verify(eventPublisher, never()).publishEvent(any(ReservationForceCancelledByBlockEvent.class));
    }

    @Test
    @DisplayName("AC-5-17: forceCancelConflicting=false も従来どおり 409")
    void forceFalseも従来どおり409() {
        seedSlot(9102L, TUESDAY);
        seedReservation(202L, APPLICANT_A, 9102L, null, true, ReservationStatus.CONFIRMED);
        givenOverlapping(row(202L, APPLICANT_A, 9102L, TUESDAY));

        assertThatThrownBy(() -> service.createRule(TEAM_ID, createRequest(false), ADMIN_ID))
                .isInstanceOf(BusinessException.class);
        assertThat(reservations.get(202L).getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("AC-5-17: 衝突が無ければ forceCancelledCount は 0（force モードで実行された事実を管理者UIが区別できる）")
    void 衝突なしのforceは0件() {
        givenOverlapping();

        RecurringBlockedTimeResponse response = service.createRule(TEAM_ID, createRequest(true), ADMIN_ID);

        assertThat(response.getForceCancelledCount()).isZero();
        verify(ruleRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("AC-5-17: 従来経路（force なし・衝突なし）では forceCancelledCount は null のまま")
    void 従来経路ではnullのまま() {
        givenOverlapping();

        RecurringBlockedTimeResponse response = service.createRule(TEAM_ID, createRequest(null), ADMIN_ID);

        assertThat(response.getForceCancelledCount())
                .as("force を使っていない登録では既存契約どおり null（additive の原則）")
                .isNull();
    }

    // ────────────────────────────────────────────────────────────
    // 強行登録の本体
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-5-17: force=true は衝突予約を ADMIN キャンセルし、枠を復帰させ、ルールを登録する")
    void 強行登録で衝突予約をキャンセルして登録する() {
        seedSlot(9110L, TUESDAY);
        seedSlot(9111L, TUESDAY.plusWeeks(1));
        ReservationEntity r1 = seedReservation(301L, APPLICANT_A, 9110L, null, true, ReservationStatus.CONFIRMED);
        ReservationEntity r2 = seedReservation(302L, APPLICANT_B, 9111L, null, true, ReservationStatus.PENDING);
        givenOverlapping(
                row(301L, APPLICANT_A, 9110L, TUESDAY),
                row(302L, APPLICANT_B, 9111L, TUESDAY.plusWeeks(1)));

        RecurringBlockedTimeResponse response = service.createRule(TEAM_ID, createRequest(true), ADMIN_ID);

        assertThat(r1.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(r2.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(r1.getCancelledBy())
                .as("管理者操作による強行キャンセルであることが監査可能であること")
                .isEqualTo(CancelledBy.ADMIN);
        assertThat(r1.getCancelReason()).as("定型文が保存されること").isNotBlank();
        assertThat(response.getForceCancelledCount()).isEqualTo(2);
        verify(ruleRepository, times(1)).save(any());

        // 枠復帰は decrementAndReopen 経由（独自にイベントを撃たない・キャンセル待ち通知の統合点）
        verify(slotService).decrementAndReopen(slots.get(9110L));
        verify(slotService).decrementAndReopen(slots.get(9111L));
    }

    @Test
    @DisplayName("AC-5-17: 強行キャンセルした各申込者へ通知イベントが発行される（黙って消さない）")
    void 各申込者へ通知イベントを発行する() {
        seedSlot(9120L, TUESDAY);
        seedSlot(9121L, TUESDAY.plusWeeks(1));
        seedReservation(401L, APPLICANT_A, 9120L, null, true, ReservationStatus.CONFIRMED);
        seedReservation(402L, APPLICANT_B, 9121L, null, true, ReservationStatus.CONFIRMED);
        givenOverlapping(
                row(401L, APPLICANT_A, 9120L, TUESDAY),
                row(402L, APPLICANT_B, 9121L, TUESDAY.plusWeeks(1)));

        service.createRule(TEAM_ID, createRequest(true), ADMIN_ID);

        ArgumentCaptor<ReservationForceCancelledByBlockEvent> captor =
                ArgumentCaptor.forClass(ReservationForceCancelledByBlockEvent.class);
        verify(eventPublisher, times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(ReservationForceCancelledByBlockEvent::getUserId)
                .containsExactlyInAnyOrder(APPLICANT_A, APPLICANT_B);
        assertThat(captor.getAllValues())
                .allSatisfy(e -> {
                    assertThat(e.getTeamId()).isEqualTo(TEAM_ID);
                    assertThat(e.getBlockReason())
                            .as("何のためにキャンセルされたのかを本人に伝える")
                            .isEqualTo("研修");
                    assertThat(e.getSlotStartAt()).isNotNull();
                });
    }

    @Test
    @DisplayName("AC-5-17: グループ予約は兄弟行まで一括キャンセルする（部分キャンセルを作らない）")
    void グループは兄弟行まで一括キャンセルする() {
        UUID groupId = UUID.fromString("018f0000-0000-7000-8000-0000000000bb");
        seedSlot(9130L, TUESDAY);
        seedSlot(9131L, TUESDAY);
        ReservationEntity primary = seedReservation(501L, APPLICANT_A, 9130L, groupId, true, ReservationStatus.CONFIRMED);
        ReservationEntity sibling = seedReservation(502L, APPLICANT_A, 9131L, groupId, false, ReservationStatus.CONFIRMED);
        // overlap 判定に引っかかるのは代表行だけ（19:00-20:00 の枠に重なるのは 1 枠のみという状況）
        givenOverlapping(row(501L, APPLICANT_A, 9130L, TUESDAY));
        given(reservationRepository.findByGroupIdAndTeamIdOrderById(groupId, TEAM_ID))
                .willReturn(List.of(primary, sibling));

        RecurringBlockedTimeResponse response = service.createRule(TEAM_ID, createRequest(true), ADMIN_ID);

        assertThat(primary.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(sibling.getStatus())
                .as("F03.4.3 が禁じる部分キャンセル（booked_count 不整合・グループ状態の分裂）を作らない")
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(response.getForceCancelledCount()).isEqualTo(2);
        verify(slotService).decrementAndReopen(slots.get(9130L));
        verify(slotService).decrementAndReopen(slots.get(9131L));
        // 通知はグループにつき 1 通（兄弟行ぶん重複送信しない）
        verify(eventPublisher, times(1)).publishEvent(any(ReservationForceCancelledByBlockEvent.class));
    }

    @Test
    @DisplayName("AC-5-17: 既に終端状態の行は二重キャンセルしない（booked_count の二重減算を防ぐ）")
    void 終端状態の行は二重キャンセルしない() {
        seedSlot(9140L, TUESDAY);
        ReservationEntity already = seedReservation(601L, APPLICANT_A, 9140L, null, true, ReservationStatus.CANCELLED);
        givenOverlapping(row(601L, APPLICANT_A, 9140L, TUESDAY));

        RecurringBlockedTimeResponse response = service.createRule(TEAM_ID, createRequest(true), ADMIN_ID);

        assertThat(response.getForceCancelledCount()).isZero();
        assertThat(already.getCancelledBy())
                .as("既に CANCELLED の行を上書きしない")
                .isNull();
        verify(slotService, never()).decrementAndReopen(any());
        verify(eventPublisher, never()).publishEvent(any(ReservationForceCancelledByBlockEvent.class));
    }

    // ────────────────────────────────────────────────────────────
    // 検分 MUST③: impact と強行キャンセルの件数が一致する
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("MUST③: impact の件数と force のキャンセル件数が一致する（グループ兄弟行を含む）")
    void impactとforceの件数が一致する() {
        // 管理者が impact で「N 件」を見て force を押したら「M 件（M>N）」消えるという
        // 嘘の事前確認を構造的に防ぐ。impact は強行登録の唯一の事前確認導線である。
        UUID groupId = UUID.fromString("018f0000-0000-7000-8000-0000000000ee");
        seedSlot(9200L, TUESDAY);
        seedSlot(9201L, TUESDAY);
        seedSlot(9202L, TUESDAY.plusWeeks(1));
        ReservationEntity primary = seedReservation(901L, APPLICANT_A, 9200L, groupId, true, ReservationStatus.CONFIRMED);
        ReservationEntity sibling = seedReservation(902L, APPLICANT_A, 9201L, groupId, false, ReservationStatus.CONFIRMED);
        ReservationEntity single = seedReservation(903L, APPLICANT_B, 9202L, null, true, ReservationStatus.CONFIRMED);
        // overlap にヒットするのは代表行と単枠の 2 件（兄弟行 902 はヒットしない状況）
        givenOverlapping(
                row(901L, APPLICANT_A, 9200L, TUESDAY),
                row(903L, APPLICANT_B, 9202L, TUESDAY.plusWeeks(1)));
        given(reservationRepository.findByGroupIdAndTeamIdOrderById(groupId, TEAM_ID))
                .willReturn(List.of(primary, sibling));

        int impactCount = service.getImpact(TEAM_ID, ReservationDayOfWeek.TUE, START, END, null)
                .getAffectedCount();

        RecurringBlockedTimeResponse response = service.createRule(TEAM_ID, createRequest(true), ADMIN_ID);

        assertThat(impactCount)
                .as("impact は兄弟行 902 も含めて 3 件を見せること（2 件と嘘をつかない）")
                .isEqualTo(3);
        assertThat(response.getForceCancelledCount())
                .as("impact の件数と force の実キャンセル件数が一致すること")
                .isEqualTo(impactCount);
        assertThat(List.of(primary, sibling, single))
                .allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELLED));
    }

    @Test
    @DisplayName("MUST③: impact の一覧にもグループ兄弟行が現れる（件数だけでなく明細も一致）")
    void impactの明細に兄弟行が現れる() {
        UUID groupId = UUID.fromString("018f0000-0000-7000-8000-0000000000ff");
        seedSlot(9210L, TUESDAY);
        seedSlot(9211L, TUESDAY);
        ReservationEntity primary = seedReservation(911L, APPLICANT_A, 9210L, groupId, true, ReservationStatus.CONFIRMED);
        ReservationEntity sibling = seedReservation(912L, APPLICANT_A, 9211L, groupId, false, ReservationStatus.CONFIRMED);
        givenOverlapping(row(911L, APPLICANT_A, 9210L, TUESDAY));
        given(reservationRepository.findByGroupIdAndTeamIdOrderById(groupId, TEAM_ID))
                .willReturn(List.of(primary, sibling));

        var impact = service.getImpact(TEAM_ID, ReservationDayOfWeek.TUE, START, END, null);

        assertThat(impact.getReservations())
                .extracting(r -> r.reservationId())
                .as("管理者が「どの予約が消えるか」を漏れなく確認できること")
                .containsExactlyInAnyOrder(911L, 912L);
        assertThat(impact.getReservations())
                .allSatisfy(r -> {
                    assertThat(r.slotDate()).as("枠の日付が解決されていること").isNotNull();
                    assertThat(r.startTime()).as("枠の開始時刻が解決されていること").isNotNull();
                });
    }

    @Test
    @DisplayName("MUST③: 衝突ゼロなら impact も force も 0 件で一致する")
    void 衝突ゼロでも一致する() {
        givenOverlapping();

        int impactCount = service.getImpact(TEAM_ID, ReservationDayOfWeek.TUE, START, END, null)
                .getAffectedCount();
        RecurringBlockedTimeResponse response = service.createRule(TEAM_ID, createRequest(true), ADMIN_ID);

        assertThat(impactCount).isZero();
        assertThat(response.getForceCancelledCount()).isEqualTo(impactCount);
    }

    // ────────────────────────────────────────────────────────────
    // PATCH（更新）でも同じ挙動
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-5-17: PATCH でも force=true で衝突予約を一括キャンセルして更新できる")
    void PATCHでも強行更新できる() {
        UUID ruleId = UUID.fromString("018f0000-0000-7000-8000-0000000000cc");
        ReservationRecurringBlockedTimeEntity existing = ReservationRecurringBlockedTimeEntity.builder()
                .teamId(TEAM_ID).lineId(null).dayOfWeek(ReservationDayOfWeek.MON)
                .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(11, 0))
                .reason("清掃").isPublic(true).isActive(true).build();
        setField(existing, "id", ruleId);
        given(ruleRepository.findByIdAndTeamId(ruleId, TEAM_ID)).willReturn(Optional.of(existing));

        seedSlot(9150L, TUESDAY);
        ReservationEntity conflict = seedReservation(701L, APPLICANT_A, 9150L, null, true, ReservationStatus.CONFIRMED);
        givenOverlapping(row(701L, APPLICANT_A, 9150L, TUESDAY));

        // 火曜 19:00-20:00 へ移動する更新（移動先に衝突予約がある）
        UpdateRecurringBlockedTimeRequest request = new UpdateRecurringBlockedTimeRequest(
                null, null, ReservationDayOfWeek.TUE, START, END, null, null, null, true);

        RecurringBlockedTimeResponse response = service.updateRule(TEAM_ID, ruleId, request, ADMIN_ID);

        assertThat(conflict.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(response.getForceCancelledCount()).isEqualTo(1);
        assertThat(existing.getDayOfWeek()).isEqualTo(ReservationDayOfWeek.TUE);
    }

    @Test
    @DisplayName("AC-5-17: PATCH の force 未指定は従来どおり 409（更新も適用されない）")
    void PATCHのforce未指定は従来どおり409() {
        UUID ruleId = UUID.fromString("018f0000-0000-7000-8000-0000000000dd");
        ReservationRecurringBlockedTimeEntity existing = ReservationRecurringBlockedTimeEntity.builder()
                .teamId(TEAM_ID).lineId(null).dayOfWeek(ReservationDayOfWeek.MON)
                .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(11, 0))
                .reason("清掃").isPublic(true).isActive(true).build();
        setField(existing, "id", ruleId);
        given(ruleRepository.findByIdAndTeamId(ruleId, TEAM_ID)).willReturn(Optional.of(existing));

        seedSlot(9160L, TUESDAY);
        ReservationEntity conflict = seedReservation(801L, APPLICANT_A, 9160L, null, true, ReservationStatus.CONFIRMED);
        givenOverlapping(row(801L, APPLICANT_A, 9160L, TUESDAY));

        UpdateRecurringBlockedTimeRequest request = new UpdateRecurringBlockedTimeRequest(
                null, null, ReservationDayOfWeek.TUE, START, END, null, null, null, null);

        assertThatThrownBy(() -> service.updateRule(TEAM_ID, ruleId, request, ADMIN_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.UNAVAILABILITY_HAS_ACTIVE_RESERVATIONS);
        assertThat(conflict.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(ruleRepository, never()).save(any());
    }
}
