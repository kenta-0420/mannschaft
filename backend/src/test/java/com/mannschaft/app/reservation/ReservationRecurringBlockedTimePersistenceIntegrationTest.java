package com.mannschaft.app.reservation;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.dto.CreateReservationRequest;
import com.mannschaft.app.reservation.dto.CreateRecurringBlockedTimeRequest;
import com.mannschaft.app.reservation.dto.RecurringBlockedTimeImpactResponse;
import com.mannschaft.app.reservation.dto.RecurringBlockedTimeResponse;
import com.mannschaft.app.reservation.dto.ReservationSlotResponse;
import com.mannschaft.app.reservation.entity.ReservationBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.entity.ReservationTeamSettingEntity;
import com.mannschaft.app.reservation.repository.ReservationBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationRecurringBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.repository.ReservationTeamSettingRepository;
import com.mannschaft.app.reservation.service.ReservationRecurringBlockedTimeService;
import com.mannschaft.app.reservation.service.ReservationService;
import com.mannschaft.app.reservation.service.ReservationSlotService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 定期予約不可枠（F03.4.5 §4 W2-2）の実 MySQL 永続化結合テスト。
 *
 * <p>TIME 型カラムの実 DB 比較（{@code feedback_localtime_max_time_type_overlap_rounding}）・
 * runtime enforcement 統合（機能B の 3 箇所+グループ）・90日horizonの409ガード境界・LINE軸enforce
 * を、モック UT では検出できない実 DB 挙動として担保する（{@code feedback_adapter_mock_ut_false_green}）。</p>
 */
@Transactional
@DisplayName("定期予約不可枠 実MySQL永続化結合テスト（F03.4.5 §4 W2-2）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReservationRecurringBlockedTimePersistenceIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ReservationRecurringBlockedTimeService ruleService;
    @Autowired
    private ReservationRecurringBlockedTimeRepository ruleRepository;
    @Autowired
    private ReservationSlotService slotService;
    @Autowired
    private ReservationService reservationService;
    @Autowired
    private ReservationSlotRepository slotRepository;
    @Autowired
    private ReservationLineRepository lineRepository;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private ReservationBlockedTimeRepository blockedTimeRepository;
    @Autowired
    private ReservationTeamSettingRepository teamSettingRepository;

    private static final Long CREATED_BY = 991000L;
    private static final Long USER_ID = 991001L;

    /** 来月・同一曜日で確実にテストを再現するため「来月の最初の月曜日」を基準日とする。 */
    private static final LocalDate NEXT_MONDAY = nextMonday();

    private static LocalDate nextMonday() {
        LocalDate d = LocalDate.now().plusMonths(1);
        while (d.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
            d = d.plusDays(1);
        }
        return d;
    }

    private void seedPublicTeam(Long teamId) {
        teamSettingRepository.save(ReservationTeamSettingEntity.builder()
                .teamId(teamId).allowPublicReservation(true).build());
    }

    private Long createSlot(Long teamId, Long lineId, LocalDate date, LocalTime start, LocalTime end) {
        return slotRepository.save(ReservationSlotEntity.builder()
                .teamId(teamId).lineId(lineId).title("W2-2結合テスト用枠")
                .slotDate(date).startTime(start).endTime(end)
                .capacity(1).build()).getId();
    }

    private void createActiveReservation(Long teamId, Long slotId, Long lineId, Long userId) {
        reservationRepository.save(ReservationEntity.builder()
                .reservationSlotId(slotId)
                .lineId(lineId)
                .teamId(teamId)
                .userId(userId)
                .status(ReservationStatus.CONFIRMED)
                .build());
    }

    // ────────────────────────────────────────────────────────────
    // R1/R3: 作成 → runtime enforcement（実DB TIME比較・半開区間）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R1/R3: 月曜19-20のルール作成後、該当slotはlistAvailableSlotsから除外され、"
            + "createReservationは400(BLOCKED_TIME_CONFLICT)。境界(18:30-19:00/20:00-20:30)は非該当")
    void R1_R3_作成後にruntimeでブロックされ境界は非該当() {
        Long teamId = 991101L;
        seedPublicTeam(teamId);
        ReservationLineEntity line = lineRepository.save(
                ReservationLineEntity.builder().teamId(teamId).name("結合テストライン").isActive(true).build());

        Long targetSlotId = createSlot(teamId, line.getId(), NEXT_MONDAY, LocalTime.of(19, 0), LocalTime.of(20, 0));
        Long beforeSlotId = createSlot(teamId, line.getId(), NEXT_MONDAY, LocalTime.of(18, 30), LocalTime.of(19, 0));
        Long afterSlotId = createSlot(teamId, line.getId(), NEXT_MONDAY, LocalTime.of(20, 0), LocalTime.of(20, 30));

        RecurringBlockedTimeResponse rule = ruleService.createRule(
                teamId,
                new CreateRecurringBlockedTimeRequest(
                        null, ReservationDayOfWeek.MON, LocalTime.of(19, 0), LocalTime.of(20, 0), "研修", true),
                CREATED_BY);
        assertThat(rule.getId()).isNotNull();

        // 実DB: listAvailableSlots から該当slotが除外され、境界slotは残る（半開区間・実TIME型比較）。
        List<ReservationSlotResponse> available =
                slotService.listAvailableSlots(teamId, USER_ID, NEXT_MONDAY, NEXT_MONDAY);
        assertThat(available).extracting(ReservationSlotResponse::getId)
                .as("19-20のslotのみ除外され、隣接する18:30-19:00/20:00-20:30は実DBでも残ること")
                .containsExactlyInAnyOrder(beforeSlotId, afterSlotId);

        // 実DB: createReservation は BLOCKED_TIME_CONFLICT(009) で拒否される。
        assertThatThrownBy(() -> reservationService.createReservation(
                teamId, USER_ID, new CreateReservationRequest(targetSlotId, line.getId(), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.BLOCKED_TIME_CONFLICT);

        // 境界slot（18:30-19:00）は実DBでも予約できる（半開区間の実務ケース・回帰なし）。
        var beforeResponse = reservationService.createReservation(
                teamId, USER_ID, new CreateReservationRequest(beforeSlotId, line.getId(), null));
        assertThat(beforeResponse).isNotNull();
    }

    // ────────────────────────────────────────────────────────────
    // R9/R8: 90日horizonの409ガード境界（実DB TIME比較）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R9: horizon内(90日以内)にactive予約があるとPOSTは409で拒否され行は増えない（実DB）")
    void R9_90日以内active予約は実DBで409() {
        Long teamId = 991102L;
        ReservationLineEntity line = lineRepository.save(
                ReservationLineEntity.builder().teamId(teamId).name("R9ライン").isActive(true).build());
        LocalDate matchDate = LocalDate.now().plusDays(30);
        while (matchDate.getDayOfWeek() != java.time.DayOfWeek.TUESDAY) {
            matchDate = matchDate.plusDays(1);
        }
        Long slotId = createSlot(teamId, line.getId(), matchDate, LocalTime.of(9, 0), LocalTime.of(10, 0));
        createActiveReservation(teamId, slotId, line.getId(), USER_ID);

        long before = ruleRepository.countByTeamId(teamId);
        assertThatThrownBy(() -> ruleService.createRule(
                teamId,
                new CreateRecurringBlockedTimeRequest(
                        null, ReservationDayOfWeek.TUE, LocalTime.of(9, 0), LocalTime.of(10, 0), "研修", true),
                CREATED_BY))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.UNAVAILABILITY_HAS_ACTIVE_RESERVATIONS);
        assertThat(ruleRepository.countByTeamId(teamId)).isEqualTo(before);
    }

    @Test
    @DisplayName("R8: 91日以降のみにactive予約があるPOSTは通る（実DBでのhorizon境界=today+90）")
    void R8_91日以降のみは実DBで通る() {
        Long teamId = 991103L;
        ReservationLineEntity line = lineRepository.save(
                ReservationLineEntity.builder().teamId(teamId).name("R8ライン").isActive(true).build());
        LocalDate farDate = LocalDate.now().plusDays(95);
        while (farDate.getDayOfWeek() != java.time.DayOfWeek.WEDNESDAY) {
            farDate = farDate.plusDays(1);
        }
        Long slotId = createSlot(teamId, line.getId(), farDate, LocalTime.of(9, 0), LocalTime.of(10, 0));
        createActiveReservation(teamId, slotId, line.getId(), USER_ID);

        RecurringBlockedTimeResponse rule = ruleService.createRule(
                teamId,
                new CreateRecurringBlockedTimeRequest(
                        null, ReservationDayOfWeek.WED, LocalTime.of(9, 0), LocalTime.of(10, 0), "研修", true),
                CREATED_BY);

        assertThat(rule.getId()).isNotNull();
        assertThat(ruleRepository.countByTeamId(teamId)).isEqualTo(1);
    }

    // ────────────────────────────────────────────────────────────
    // LINE軸enforce（単発blocked_times・実DB）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LINE軸enforce: resource_type=LINEの単発blocked_timesはline_id一致slotを実DBでブロックする")
    void LINE軸enforceは実DBで機能する() {
        Long teamId = 991104L;
        seedPublicTeam(teamId);
        ReservationLineEntity targetLine = lineRepository.save(
                ReservationLineEntity.builder().teamId(teamId).name("対象ライン").isActive(true).build());
        ReservationLineEntity otherLine = lineRepository.save(
                ReservationLineEntity.builder().teamId(teamId).name("別ライン").isActive(true).build());
        LocalDate date = LocalDate.now().plusMonths(1);
        Long targetSlotId = createSlot(teamId, targetLine.getId(), date, LocalTime.of(10, 0), LocalTime.of(11, 0));
        Long otherSlotId = createSlot(teamId, otherLine.getId(), date, LocalTime.of(10, 0), LocalTime.of(11, 0));

        blockedTimeRepository.save(ReservationBlockedTimeEntity.builder()
                .teamId(teamId).blockedDate(date).startTime(null).endTime(null)
                .reason("設備点検").resourceType(ReservationBlockedResourceType.LINE)
                .resourceId(targetLine.getId()).build());

        // 対象ラインの枠は拒否される。
        assertThatThrownBy(() -> reservationService.createReservation(
                teamId, USER_ID, new CreateReservationRequest(targetSlotId, targetLine.getId(), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.BLOCKED_TIME_CONFLICT);

        // 別ラインの枠は影響を受けない（実DBでも回帰なし）。
        var otherResponse = reservationService.createReservation(
                teamId, USER_ID, new CreateReservationRequest(otherSlotId, otherLine.getId(), null));
        assertThat(otherResponse).isNotNull();
    }

    // ────────────────────────────────────────────────────────────
    // R5: is_active=FALSE で即予約可（実DB）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R5: is_active=FALSE化後は実DBでも即予約可（runtime判定対象外）")
    void R5_無効化後は実DBで即予約可() {
        Long teamId = 991105L;
        seedPublicTeam(teamId);
        ReservationLineEntity line = lineRepository.save(
                ReservationLineEntity.builder().teamId(teamId).name("R5ライン").isActive(true).build());
        Long slotId = createSlot(teamId, line.getId(), NEXT_MONDAY, LocalTime.of(15, 0), LocalTime.of(16, 0));

        RecurringBlockedTimeResponse rule = ruleService.createRule(
                teamId,
                new CreateRecurringBlockedTimeRequest(
                        null, ReservationDayOfWeek.MON, LocalTime.of(15, 0), LocalTime.of(16, 0), "研修", true),
                CREATED_BY);

        // 有効な間は拒否される。
        assertThatThrownBy(() -> reservationService.createReservation(
                teamId, USER_ID, new CreateReservationRequest(slotId, line.getId(), null)))
                .isInstanceOf(BusinessException.class);

        // is_active=FALSE 化。
        ruleService.updateRule(teamId, rule.getId(),
                new com.mannschaft.app.reservation.dto.UpdateRecurringBlockedTimeRequest(
                        null, null, null, null, null, null, null, false),
                CREATED_BY);

        // 無効化後は実DBでも即予約可。
        var response = reservationService.createReservation(
                teamId, USER_ID, new CreateReservationRequest(slotId, line.getId(), null));
        assertThat(response).isNotNull();
    }

    // ────────────────────────────────────────────────────────────
    // impact（副作用ゼロ・実DB）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("impact: 実DBでoverlapするactive予約の件数・日付を返す（副作用ゼロ）")
    void impactは実DBで件数を返す() {
        Long teamId = 991106L;
        ReservationLineEntity line = lineRepository.save(
                ReservationLineEntity.builder().teamId(teamId).name("impactライン").isActive(true).build());
        LocalDate matchDate = LocalDate.now().plusDays(15);
        while (matchDate.getDayOfWeek() != java.time.DayOfWeek.THURSDAY) {
            matchDate = matchDate.plusDays(1);
        }
        Long slotId = createSlot(teamId, line.getId(), matchDate, LocalTime.of(14, 0), LocalTime.of(15, 0));
        createActiveReservation(teamId, slotId, line.getId(), USER_ID);

        RecurringBlockedTimeImpactResponse impact = ruleService.getImpact(
                teamId, ReservationDayOfWeek.THU, LocalTime.of(14, 0), LocalTime.of(15, 0), null);

        assertThat(impact.getAffectedCount()).isEqualTo(1);
        assertThat(impact.getReservations().get(0).slotDate()).isEqualTo(matchDate);
        // 副作用ゼロ: ルールは作られていない。
        assertThat(ruleRepository.countByTeamId(teamId)).isZero();
    }
}
