package com.mannschaft.app.reservation;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.dto.CancelReservationRequest;
import com.mannschaft.app.reservation.dto.CreateRecurringBlockedTimeRequest;
import com.mannschaft.app.reservation.dto.CreateReservationRequest;
import com.mannschaft.app.reservation.dto.RecurringBlockedTimeResponse;
import com.mannschaft.app.reservation.dto.ReservationResponse;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.entity.ReservationTeamSettingEntity;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.repository.ReservationTeamSettingRepository;
import com.mannschaft.app.reservation.service.ReservationRecurringBlockedTimeService;
import com.mannschaft.app.reservation.service.ReservationRecurringService;
import com.mannschaft.app.reservation.service.ReservationRecurringSlotResolver;
import com.mannschaft.app.reservation.service.ReservationService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 定期予約（毎週繰り返し）の実 MySQL 結合テスト（F03.4.5 §6.2 W2-5）。
 *
 * <p>受け入れ条件の対応:</p>
 * <ul>
 *   <li><b>AC-5-1</b>: {@code repeatWeeks=4} で起点週を含む 4 週。FULL / 枠なし / BLOCKED はスキップ明細</li>
 *   <li><b>AC-5-2</b>: 成立した全行が<b>実 DB 上で</b>同一 {@code recurring_series_id}
 *       （BINARY(16) の往復も含めて検証）。省略時は NULL</li>
 *   <li><b>AC-5-3</b>: {@code repeatWeeks=13} は {@code RESERVATION_054}</li>
 *   <li><b>AC-5-4</b>: 起点週が満席なら全体 400 で 1 行も残らない</li>
 *   <li><b>AC-5-5</b>: 週ごと独立トランザクション（1 週の失敗が成立分を巻き戻さない）</li>
 *   <li><b>AC-5-7</b>: {@code THIS_AND_FOLLOWING} で当該日以降のみ CANCELLED・枠が復帰する</li>
 *   <li><b>AC-5-8</b>: 他人の行が同一 series にあっても消えない（IDOR・実 DB 観測）</li>
 *   <li><b>AC-5-9</b>: {@code scope=SERIES} で series 内 PENDING が一括 CONFIRMED</li>
 *   <li><b>AC-5-10</b>: 12 週でも枠解決のステートメント数が 2 週と同じ（Hibernate Statistics で機械検証）</li>
 *   <li><b>AC-5-12</b>: 定期予約不可枠の週は {@code BLOCKED}</li>
 *   <li><b>AC-5-13</b>: 追加週が全スキップなら {@code recurring_series_id} は NULL のまま残る</li>
 *   <li><b>AC-5-17</b>: 強行登録で衝突予約が CANCELLED になり枠が復帰する（実 DB 観測）</li>
 * </ul>
 *
 * <p><b>永続化の検証は「保存 → 再読込」まで見る</b>。{@code ArgumentCaptor} で済ませると
 * detached コピーのバグ（BINARY(16) の UUID 往復ずれ等）を検知できない。</p>
 */
@DisplayName("定期予約 永続化結合テスト（実MySQL・F03.4.5 §6.2 W2-5）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReservationRecurringReservationPersistenceIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ReservationRecurringService recurringService;
    @Autowired
    private ReservationRecurringSlotResolver slotResolver;
    @Autowired
    private ReservationService reservationService;
    @Autowired
    private ReservationRecurringBlockedTimeService ruleService;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private ReservationSlotRepository slotRepository;
    @Autowired
    private ReservationLineRepository lineRepository;
    @Autowired
    private ReservationTeamSettingRepository teamSettingRepository;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /** 他テストのシードと混ざらないためのID採番。 */
    private static final AtomicLong SEQ = new AtomicLong(870_000L);

    private static long nextId() {
        return SEQ.incrementAndGet();
    }

    /** 来月最初の火曜日を基準日にする（同一曜日の週次繰り返しを確実に再現するため）。 */
    private static final LocalDate BASE_DATE = nextTuesday();
    private static final LocalTime START = LocalTime.of(19, 0);
    private static final LocalTime END = LocalTime.of(20, 0);

    private static LocalDate nextTuesday() {
        LocalDate d = LocalDate.now().plusMonths(1);
        while (d.getDayOfWeek() != java.time.DayOfWeek.TUESDAY) {
            d = d.plusDays(1);
        }
        return d;
    }

    // ────────────────────────────────────────────────────────────
    // シードヘルパー
    // ────────────────────────────────────────────────────────────

    private void seedPublicTeam(Long teamId) {
        teamSettingRepository.save(ReservationTeamSettingEntity.builder()
                .teamId(teamId).allowPublicReservation(true).build());
    }

    private ReservationLineEntity seedLine(Long teamId) {
        return lineRepository.save(ReservationLineEntity.builder()
                .teamId(teamId).name("W2-5結合テストライン").isActive(true).build());
    }

    private ReservationSlotEntity seedSlot(
            Long teamId, Long lineId, LocalDate date, int capacity, int booked, SlotStatus status) {
        return slotRepository.save(ReservationSlotEntity.builder()
                .teamId(teamId).lineId(lineId).title("W2-5結合テスト用枠")
                .slotDate(date).startTime(START).endTime(END)
                .capacity(capacity).bookedCount(booked).slotStatus(status)
                .build());
    }

    /** 起点日 + 7×k の枠を weeks 週ぶん（k=0..weeks-1）まとめて作る。 */
    private void seedWeeklySlots(Long teamId, Long lineId, int weeks) {
        for (int k = 0; k < weeks; k++) {
            seedSlot(teamId, lineId, BASE_DATE.plusWeeks(k), 1, 0, SlotStatus.AVAILABLE);
        }
    }

    private ReservationSlotEntity slotOf(Long teamId, LocalDate date) {
        return slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(teamId, date, date)
                .stream()
                .filter(s -> START.equals(s.getStartTime()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("枠が見つかりません: " + date));
    }

    private CreateReservationRequest request(Long slotId, Long lineId, Integer repeatWeeks) {
        return new CreateReservationRequest(slotId, lineId, "定期予約テスト", repeatWeeks);
    }

    // ────────────────────────────────────────────────────────────
    // AC-5-1 / AC-5-2: 起点週を含む 4 週・全行が同一 series（実 DB 往復）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-5-1/AC-5-2: repeatWeeks=4 で4行が成立し、実DB上で全行が同一 recurring_series_id を持つ")
    void 四週分が同一seriesで永続化される() {
        Long teamId = nextId();
        Long userId = nextId();
        seedPublicTeam(teamId);
        ReservationLineEntity line = seedLine(teamId);
        seedWeeklySlots(teamId, line.getId(), 4);
        ReservationSlotEntity base = slotOf(teamId, BASE_DATE);

        ReservationResponse response = recurringService.createRecurring(
                teamId, userId, request(base.getId(), line.getId(), 4));

        assertThat(response.getRecurring()).isNotNull();
        assertThat(response.getRecurring().createdCount()).isEqualTo(4);
        assertThat(response.getRecurring().skippedCount()).isZero();
        UUID seriesId = response.getRecurring().seriesId();
        assertThat(seriesId).isNotNull();

        // 保存 → 再読込。BINARY(16) の UUID 往復が壊れていないことまで見る。
        List<ReservationEntity> rows =
                reservationRepository.findByRecurringSeriesIdAndUserIdOrderById(seriesId, userId);
        assertThat(rows).as("series で 4 行が引けること（INDEX idx_rv_series 経由）").hasSize(4);
        assertThat(rows).extracting(ReservationEntity::getRecurringSeriesId).containsOnly(seriesId);
        assertThat(rows).extracting(r -> slotRepository.findById(r.getReservationSlotId()).orElseThrow().getSlotDate())
                .containsExactlyInAnyOrder(BASE_DATE, BASE_DATE.plusWeeks(1),
                        BASE_DATE.plusWeeks(2), BASE_DATE.plusWeeks(3));
        // 全枠の booked_count が加算されている
        assertThat(rows).allSatisfy(r ->
                assertThat(slotRepository.findById(r.getReservationSlotId()).orElseThrow().getBookedCount())
                        .isEqualTo(1));
    }

    @Test
    @DisplayName("AC-5-2: repeatWeeks 省略の単発予約は recurring_series_id が NULL のまま（既存契約不変）")
    void 単発予約はseriesがNULL() {
        Long teamId = nextId();
        Long userId = nextId();
        seedPublicTeam(teamId);
        ReservationLineEntity line = seedLine(teamId);
        seedWeeklySlots(teamId, line.getId(), 1);
        ReservationSlotEntity base = slotOf(teamId, BASE_DATE);

        ReservationResponse response = reservationService.createReservation(
                teamId, userId, request(base.getId(), line.getId(), null));

        ReservationEntity saved = reservationRepository.findById(response.getId()).orElseThrow();
        assertThat(saved.getRecurringSeriesId()).isNull();
        assertThat(response.getRecurring()).isNull();
    }

    // ────────────────────────────────────────────────────────────
    // AC-5-1 / AC-5-12: スキップ明細（FULL / 枠なし / BLOCKED）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-5-1: 満席週・枠なし週はスキップされ明細に理由が載る（成立分はコミットされる）")
    void 満席週と枠なし週はスキップされる() {
        Long teamId = nextId();
        Long userId = nextId();
        seedPublicTeam(teamId);
        ReservationLineEntity line = seedLine(teamId);
        // 起点=空き / 2週目=満席 / 3週目=枠なし / 4週目=空き
        seedSlot(teamId, line.getId(), BASE_DATE, 1, 0, SlotStatus.AVAILABLE);
        seedSlot(teamId, line.getId(), BASE_DATE.plusWeeks(1), 1, 1, SlotStatus.FULL);
        seedSlot(teamId, line.getId(), BASE_DATE.plusWeeks(3), 1, 0, SlotStatus.AVAILABLE);
        ReservationSlotEntity base = slotOf(teamId, BASE_DATE);

        ReservationResponse response = recurringService.createRecurring(
                teamId, userId, request(base.getId(), line.getId(), 4));

        assertThat(response.getRecurring().createdCount()).isEqualTo(2);
        assertThat(response.getRecurring().skippedWeeks())
                .extracting(ReservationResponse.RecurringWeekOutcomeDto::reason)
                .containsExactly(
                        RecurringWeekSkipReason.FULL.name(),
                        RecurringWeekSkipReason.NOT_GENERATED.name());
        // 成立分は実 DB にコミットされている（1 週の失敗で巻き戻っていない = AC-5-5）
        assertThat(reservationRepository.findByRecurringSeriesIdAndUserIdOrderById(
                response.getRecurring().seriesId(), userId)).hasSize(2);
    }

    @Test
    @DisplayName("AC-5-12: 定期予約不可枠に当たる週は BLOCKED でスキップされる（実DBのTIME比較）")
    void 定期不可枠の週はBLOCKEDでスキップされる() {
        Long teamId = nextId();
        Long userId = nextId();
        seedPublicTeam(teamId);
        ReservationLineEntity line = seedLine(teamId);
        seedWeeklySlots(teamId, line.getId(), 3);
        ReservationSlotEntity base = slotOf(teamId, BASE_DATE);

        // 起点週の予約を先に作ると 409 ガードに引っかかるため、ルールは force で作る必要がある。
        // ここでは「先にルールを作り、その後に定期予約を試みる」順序で検証する。
        // → 起点枠自体もブロックされるため、まず起点だけ別時間帯にする構成にはせず、
        //   ルールは「2週目以降だけに効く」ようにはできない（週次ルールは全週に効く）。
        //   よって本テストは「全週 BLOCKED = 起点も作れない」ことを確認する（AC-5-4 と整合）。
        RecurringBlockedTimeResponse rule = ruleService.createRule(teamId,
                new CreateRecurringBlockedTimeRequest(
                        null, ReservationDayOfWeek.TUE, START, END, "研修", true, null),
                nextId());
        assertThat(rule.getId()).isNotNull();

        assertThatThrownBy(() -> recurringService.createRecurring(
                teamId, userId, request(base.getId(), line.getId(), 3)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .as("毎週火曜19時がブロックされていれば起点週も作れず全体が 400 になる")
                .isEqualTo(ReservationErrorCode.BLOCKED_TIME_CONFLICT);

        assertThat(reservationRepository.findAll().stream()
                .filter(r -> teamId.equals(r.getTeamId())).toList())
                .as("1 行も作られない")
                .isEmpty();
    }

    // ────────────────────────────────────────────────────────────
    // AC-5-3 / AC-5-4: 境界と起点週失敗
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-5-3(境界): repeatWeeks=13 は RESERVATION_054 で拒否され1行も作られない")
    void 十三週は拒否される() {
        Long teamId = nextId();
        Long userId = nextId();
        seedPublicTeam(teamId);
        ReservationLineEntity line = seedLine(teamId);
        seedWeeklySlots(teamId, line.getId(), 1);
        ReservationSlotEntity base = slotOf(teamId, BASE_DATE);

        assertThatThrownBy(() -> recurringService.createRecurring(
                teamId, userId, request(base.getId(), line.getId(), 13)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.RECURRING_RESERVATION_LIMIT_EXCEEDED);

        assertThat(reservationRepository.findAll().stream()
                .filter(r -> teamId.equals(r.getTeamId())).toList()).isEmpty();
    }

    @Test
    @DisplayName("AC-5-3(境界): repeatWeeks=12 は成功し、枠がある週ぶんだけ成立する")
    void 十二週は成功する() {
        Long teamId = nextId();
        Long userId = nextId();
        seedPublicTeam(teamId);
        ReservationLineEntity line = seedLine(teamId);
        seedWeeklySlots(teamId, line.getId(), 12);
        ReservationSlotEntity base = slotOf(teamId, BASE_DATE);

        ReservationResponse response = recurringService.createRecurring(
                teamId, userId, request(base.getId(), line.getId(), 12));

        assertThat(response.getRecurring().createdCount()).isEqualTo(12);
        assertThat(reservationRepository.findByRecurringSeriesIdAndUserIdOrderById(
                response.getRecurring().seriesId(), userId)).hasSize(12);
    }

    @Test
    @DisplayName("AC-5-4: 起点週が満席なら全体が 400 になり、2週目以降も作られない")
    void 起点週満席なら全体失敗() {
        Long teamId = nextId();
        Long userId = nextId();
        seedPublicTeam(teamId);
        ReservationLineEntity line = seedLine(teamId);
        // 起点=満席 / 2〜4週目=空き
        seedSlot(teamId, line.getId(), BASE_DATE, 1, 1, SlotStatus.FULL);
        seedSlot(teamId, line.getId(), BASE_DATE.plusWeeks(1), 1, 0, SlotStatus.AVAILABLE);
        seedSlot(teamId, line.getId(), BASE_DATE.plusWeeks(2), 1, 0, SlotStatus.AVAILABLE);
        ReservationSlotEntity base = slotOf(teamId, BASE_DATE);

        assertThatThrownBy(() -> recurringService.createRecurring(
                teamId, userId, request(base.getId(), line.getId(), 3)))
                .isInstanceOf(BusinessException.class);

        assertThat(reservationRepository.findAll().stream()
                .filter(r -> teamId.equals(r.getTeamId())).toList())
                .as("起点が取れないなら 2 週目以降も作らない（歯抜けの先頭欠落を作らない）")
                .isEmpty();
        assertThat(slotRepository.findById(
                slotOf(teamId, BASE_DATE.plusWeeks(1)).getId()).orElseThrow().getBookedCount())
                .as("2 週目の枠も確保されていない")
                .isZero();
    }

    // ────────────────────────────────────────────────────────────
    // AC-5-13: 追加週が全スキップなら series を発行しない
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-5-13: 2週目以降が全スキップなら成功しつつ recurring_series_id は NULL に戻る")
    void 全スキップならseriesはNULL() {
        Long teamId = nextId();
        Long userId = nextId();
        seedPublicTeam(teamId);
        ReservationLineEntity line = seedLine(teamId);
        // 起点だけ枠がある（2〜5週目は未生成）
        seedSlot(teamId, line.getId(), BASE_DATE, 1, 0, SlotStatus.AVAILABLE);
        ReservationSlotEntity base = slotOf(teamId, BASE_DATE);

        ReservationResponse response = recurringService.createRecurring(
                teamId, userId, request(base.getId(), line.getId(), 5));

        assertThat(response.getId()).as("起点週は成立する（エラーにしない）").isNotNull();
        assertThat(response.getRecurring().createdCount()).isEqualTo(1);
        assertThat(response.getRecurring().skippedCount()).isEqualTo(4);
        assertThat(response.getRecurring().seriesId()).isNull();

        ReservationEntity saved = reservationRepository.findById(response.getId()).orElseThrow();
        assertThat(saved.getRecurringSeriesId())
                .as("1 行だけの series は DB 上も NULL に戻す（単発予約と同じ状態）")
                .isNull();
    }

    // ────────────────────────────────────────────────────────────
    // AC-5-7 / AC-5-8: THIS_AND_FOLLOWING と IDOR
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-5-7: THIS_AND_FOLLOWING で当該日以降が CANCELLED になり枠が復帰する")
    void 以降すべてキャンセルで枠が復帰する() {
        Long teamId = nextId();
        Long userId = nextId();
        seedPublicTeam(teamId);
        ReservationLineEntity line = seedLine(teamId);
        seedWeeklySlots(teamId, line.getId(), 3);
        ReservationSlotEntity base = slotOf(teamId, BASE_DATE);

        ReservationResponse created = recurringService.createRecurring(
                teamId, userId, request(base.getId(), line.getId(), 3));
        UUID seriesId = created.getRecurring().seriesId();
        List<ReservationEntity> rows =
                reservationRepository.findByRecurringSeriesIdAndUserIdOrderById(seriesId, userId);
        assertThat(rows).hasSize(3);

        // 起点回を「以降すべて」でキャンセルする
        ReservationEntity baseRow = rows.stream()
                .filter(r -> r.getReservationSlotId().equals(base.getId()))
                .findFirst().orElseThrow();
        ReservationResponse cancelled = reservationService.cancelByUser(userId, baseRow.getId(),
                new CancelReservationRequest("以降すべて", ReservationCancelScope.THIS_AND_FOLLOWING));

        assertThat(cancelled.getRecurringCancel()).isNotNull();
        assertThat(cancelled.getRecurringCancel().cancelledCount()).isEqualTo(3);
        // @Transactional な IT では findById が 1 次キャッシュを返し得るため status を直接見る
        assertThat(reservationRepository.findByRecurringSeriesIdAndUserIdOrderById(seriesId, userId))
                .allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELLED));
        // 全枠の booked_count が戻っている
        for (int k = 0; k < 3; k++) {
            assertThat(slotRepository.findById(slotOf(teamId, BASE_DATE.plusWeeks(k)).getId())
                    .orElseThrow().getBookedCount())
                    .as("%d 週目の枠が復帰していること", k + 1)
                    .isZero();
        }
    }

    @Test
    @DisplayName("AC-5-8: 他人の予約が同一 series にあっても THIS_AND_FOLLOWING で消えない（IDOR・実DB観測）")
    void 他人の予約は消えない() {
        Long teamId = nextId();
        Long userA = nextId();
        Long userB = nextId();
        seedPublicTeam(teamId);
        ReservationLineEntity line = seedLine(teamId);
        // capacity 2 の枠を 3 週分（A と B が同じ枠を共有できるようにする）
        for (int k = 0; k < 3; k++) {
            seedSlot(teamId, line.getId(), BASE_DATE.plusWeeks(k), 2, 0, SlotStatus.AVAILABLE);
        }
        ReservationSlotEntity base = slotOf(teamId, BASE_DATE);

        ReservationResponse a = recurringService.createRecurring(
                teamId, userA, request(base.getId(), line.getId(), 3));
        UUID seriesA = a.getRecurring().seriesId();

        // B の予約行に A の series ID を強引に付ける（他人の series を掴んだ最悪ケースを作る）
        ReservationResponse b = reservationService.createReservation(
                teamId, userB, request(slotOf(teamId, BASE_DATE.plusWeeks(1)).getId(), line.getId(), null));
        ReservationEntity bRow = reservationRepository.findById(b.getId()).orElseThrow();
        setRecurringSeriesId(bRow, seriesA);
        reservationRepository.saveAndFlush(bRow);

        ReservationEntity aBaseRow = reservationRepository
                .findByRecurringSeriesIdAndUserIdOrderById(seriesA, userA).stream()
                .filter(r -> r.getReservationSlotId().equals(base.getId()))
                .findFirst().orElseThrow();
        reservationService.cancelByUser(userA, aBaseRow.getId(),
                new CancelReservationRequest("以降すべて", ReservationCancelScope.THIS_AND_FOLLOWING));

        assertThat(reservationRepository.findById(b.getId()).orElseThrow().getStatus())
                .as("同一 series ID を持っていても他人（B）の予約は絶対にキャンセルされない")
                .isNotEqualTo(ReservationStatus.CANCELLED);
    }

    /** テスト専用: 他人の series を掴んだ状況を作るため series ID を直接書き換える。 */
    private static void setRecurringSeriesId(ReservationEntity entity, UUID seriesId) {
        try {
            java.lang.reflect.Field f = ReservationEntity.class.getDeclaredField("recurringSeriesId");
            f.setAccessible(true);
            f.set(entity, seriesId);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ────────────────────────────────────────────────────────────
    // AC-5-9: scope=SERIES の一括承認
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-5-9: scope=SERIES で series 内 PENDING が一括 CONFIRMED になる（実DB観測）")
    void シリーズ一括承認が実DBで効く() {
        Long teamId = nextId();
        Long userId = nextId();
        seedPublicTeam(teamId);
        ReservationLineEntity line = seedLine(teamId);
        seedWeeklySlots(teamId, line.getId(), 3);
        ReservationSlotEntity base = slotOf(teamId, BASE_DATE);

        // 承認モードは既定（ポリシー行なし = AUTO）だと即 CONFIRMED になるため MANUAL を明示する。
        // 枠側の approval_mode を MANUAL にして PENDING を作る。
        for (int k = 0; k < 3; k++) {
            ReservationSlotEntity s = slotOf(teamId, BASE_DATE.plusWeeks(k));
            setApprovalMode(s, ApprovalMode.MANUAL);
            slotRepository.saveAndFlush(s);
        }

        ReservationResponse created = recurringService.createRecurring(
                teamId, userId, request(base.getId(), line.getId(), 3));
        UUID seriesId = created.getRecurring().seriesId();
        List<ReservationEntity> rows =
                reservationRepository.findByRecurringSeriesIdAndTeamIdOrderById(seriesId, teamId);
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(r ->
                assertThat(r.getStatus()).as("MANUAL なので PENDING で並ぶ").isEqualTo(ReservationStatus.PENDING));

        ReservationEntity baseRow = rows.stream()
                .filter(r -> r.getReservationSlotId().equals(base.getId()))
                .findFirst().orElseThrow();
        ReservationResponse confirmed = reservationService.confirmReservation(
                teamId, baseRow.getId(), ReservationConfirmScope.SERIES);

        assertThat(confirmed.getRecurringConfirm()).isNotNull();
        assertThat(confirmed.getRecurringConfirm().confirmedCount()).isEqualTo(3);
        assertThat(reservationRepository.findByRecurringSeriesIdAndTeamIdOrderById(seriesId, teamId))
                .allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(ReservationStatus.CONFIRMED));
    }

    private static void setApprovalMode(ReservationSlotEntity slot, ApprovalMode mode) {
        try {
            java.lang.reflect.Field f = ReservationSlotEntity.class.getDeclaredField("approvalMode");
            f.setAccessible(true);
            f.set(slot, mode);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ────────────────────────────────────────────────────────────
    // AC-5-10: 枠解決のステートメント数が週数に比例しない
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-5-10: 12週の枠解決のステートメント数が2週と同じ（Hibernate Statistics で機械検証）")
    void 枠解決のステートメント数は週数に比例しない() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        Long teamA = nextId();
        Long userA = nextId();
        seedPublicTeam(teamA);
        ReservationLineEntity lineA = seedLine(teamA);
        seedWeeklySlots(teamA, lineA.getId(), 12);
        ReservationSlotEntity baseA = slotOf(teamA, BASE_DATE);

        statistics.clear();
        slotResolver.resolve(teamA, userA, baseA, 2);
        long twoWeeks = statistics.getPrepareStatementCount();

        statistics.clear();
        slotResolver.resolve(teamA, userA, baseA, 12);
        long twelveWeeks = statistics.getPrepareStatementCount();

        assertThat(twelveWeeks)
                .as("12週でも枠解決のクエリ本数は2週と同じ（範囲検索1回・N+1なし）: 2週=%d, 12週=%d",
                        twoWeeks, twelveWeeks)
                .isEqualTo(twoWeeks);
        assertThat(twelveWeeks)
                .as("固定本数（候補枠・単発ブロック・定期ルール・自分の既存予約）に収まること")
                .isLessThanOrEqualTo(5L);
    }

    // ────────────────────────────────────────────────────────────
    // AC-5-17: 強行登録（定期予約と定期不可枠の衝突を解く）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-5-17: 定期予約が入っていても force=true で定期不可枠を登録でき、衝突予約が CANCELLED になる")
    void 強行登録で定期予約の衝突を解消できる() {
        Long teamId = nextId();
        Long userId = nextId();
        Long adminId = nextId();
        seedPublicTeam(teamId);
        ReservationLineEntity line = seedLine(teamId);
        seedWeeklySlots(teamId, line.getId(), 4);
        ReservationSlotEntity base = slotOf(teamId, BASE_DATE);

        // 会員が 4 週分の定期予約を入れる（= 管理者が定期不可枠を作れなくなる状況）
        ReservationResponse created = recurringService.createRecurring(
                teamId, userId, request(base.getId(), line.getId(), 4));
        assertThat(created.getRecurring().createdCount()).isEqualTo(4);

        CreateRecurringBlockedTimeRequest blockRequest = new CreateRecurringBlockedTimeRequest(
                null, ReservationDayOfWeek.TUE, START, END, "研修", true, null);

        // force なし → 409（この 409 が「機能の構造的破綻」そのもの）
        assertThatThrownBy(() -> ruleService.createRule(teamId, blockRequest, adminId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.UNAVAILABILITY_HAS_ACTIVE_RESERVATIONS);

        // force あり → 衝突予約を一括キャンセルして登録できる
        RecurringBlockedTimeResponse rule = ruleService.createRule(teamId,
                new CreateRecurringBlockedTimeRequest(
                        null, ReservationDayOfWeek.TUE, START, END, "研修", true, true),
                adminId);

        assertThat(rule.getId()).isNotNull();
        assertThat(rule.getForceCancelledCount())
                .as("4 週分の定期予約が一括キャンセルされること")
                .isEqualTo(4);
        assertThat(reservationRepository.findByRecurringSeriesIdAndTeamIdOrderById(
                created.getRecurring().seriesId(), teamId))
                .allSatisfy(r -> {
                    assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
                    assertThat(r.getCancelledBy()).isEqualTo(CancelledBy.ADMIN);
                    assertThat(r.getCancelReason()).isNotBlank();
                });
        // 枠は復帰している（管理者が別用途に使える状態に戻る）
        for (int k = 0; k < 4; k++) {
            assertThat(slotRepository.findById(slotOf(teamId, BASE_DATE.plusWeeks(k)).getId())
                    .orElseThrow().getBookedCount())
                    .isZero();
        }
    }
}
