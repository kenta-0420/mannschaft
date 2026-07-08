package com.mannschaft.app.reservation;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.reservation.dto.CreateReservationRequest;
import com.mannschaft.app.reservation.dto.WaitlistCountResponse;
import com.mannschaft.app.reservation.dto.WaitlistEntryResponse;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.entity.ReservationTeamSettingEntity;
import com.mannschaft.app.reservation.entity.ReservationWaitlistEntryEntity;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.repository.ReservationTeamSettingRepository;
import com.mannschaft.app.reservation.repository.ReservationWaitlistEntryRepository;
import com.mannschaft.app.reservation.service.ReservationService;
import com.mannschaft.app.reservation.service.ReservationWaitlistService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * キャンセル待ち（waitlist）の実 MySQL 永続化結合テスト（F03.4.5 §6.1・受け入れ条件 W-1/W-2 の DB 観測点）。
 *
 * <ul>
 *   <li><b>W-1</b>: 満席枠登録で WAITING 行が 1 件・本人取消で CANCELLED</li>
 *   <li><b>W-1(IDOR)</b>: 他人が取消しても本人 WAITING が無いため 404・A のエントリは無傷</li>
 *   <li><b>W-1(count)</b>: 他チームの slot への件数取得は 404 秘匿</li>
 *   <li><b>W-2(通知)</b>: notifySlotReopened で WAITING 全員に通知行が作られ notified_at が立つ・60 分以内は再送しない</li>
 *   <li><b>W-2(枠単位重複なし)</b>: 別枠の待機者は当該枠の通知に混ざらない</li>
 *   <li><b>W-2(CONVERTED)</b>: 予約成立で同一 (slot, user) の WAITING が CONVERTED になる</li>
 *   <li><b>自動失効</b>: 枠開始を過ぎた WAITING が物理削除され、未来枠は残る</li>
 * </ul>
 */
@DisplayName("キャンセル待ち 永続化結合テスト（実MySQL・F03.4.5 §6.1）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReservationWaitlistPersistenceIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ReservationWaitlistService waitlistService;
    @Autowired
    private ReservationService reservationService;
    @Autowired
    private ReservationWaitlistEntryRepository waitlistRepository;
    @Autowired
    private ReservationSlotRepository slotRepository;
    @Autowired
    private ReservationLineRepository lineRepository;
    @Autowired
    private ReservationTeamSettingRepository teamSettingRepository;
    @Autowired
    private NotificationRepository notificationRepository;

    private static final LocalDate FUTURE = LocalDate.now().plusMonths(1);

    private void seedPublicTeam(Long teamId) {
        teamSettingRepository.save(ReservationTeamSettingEntity.builder()
                .teamId(teamId).allowPublicReservation(true).build());
    }

    private Long seedSlot(Long teamId, LocalDate date, LocalTime start, SlotStatus status, int capacity, int booked) {
        return slotRepository.save(ReservationSlotEntity.builder()
                .teamId(teamId).title("枠").slotDate(date).startTime(start).endTime(start.plusMinutes(30))
                .capacity(capacity).bookedCount(booked).slotStatus(status).build()).getId();
    }

    // ────────────────────────────────────────────────────────────
    // W-1
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("W-1: 満席枠へ登録 → WAITING 1 行、本人取消 → CANCELLED")
    void 登録と本人取消のDB観測() {
        Long teamId = 970001L;
        seedPublicTeam(teamId);
        Long slotId = seedSlot(teamId, FUTURE, LocalTime.of(10, 0), SlotStatus.FULL, 1, 1);
        Long userId = 970101L;

        WaitlistEntryResponse resp = waitlistService.register(teamId, slotId, userId);
        assertThat(resp.getStatus()).isEqualTo("WAITING");
        assertThat(waitlistRepository.countBySlotIdAndStatus(slotId, WaitlistStatus.WAITING)).isEqualTo(1L);

        waitlistService.cancelOwn(teamId, slotId, userId);
        assertThat(waitlistRepository.countBySlotIdAndStatus(slotId, WaitlistStatus.WAITING)).isZero();
        assertThat(waitlistRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, WaitlistStatus.CANCELLED))
                .hasSize(1);
    }

    @Test
    @DisplayName("W-1(IDOR): 他ユーザーの取消は 404、A のエントリは無傷")
    void 他人取消は404_AのエントリはIDOR秘匿() {
        Long teamId = 970002L;
        seedPublicTeam(teamId);
        Long slotId = seedSlot(teamId, FUTURE, LocalTime.of(11, 0), SlotStatus.FULL, 1, 1);
        Long userA = 970201L;
        Long userB = 970202L;
        waitlistService.register(teamId, slotId, userA);

        assertThatThrownBy(() -> waitlistService.cancelOwn(teamId, slotId, userB))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.WAITLIST_ENTRY_NOT_FOUND);
        // A のエントリは WAITING のまま無傷
        assertThat(waitlistRepository.existsBySlotIdAndUserIdAndStatus(slotId, userA, WaitlistStatus.WAITING)).isTrue();
    }

    @Test
    @DisplayName("W-1(count): 他チームの slot への件数取得は 404 秘匿")
    void 他チームslot件数は404() {
        Long teamId = 970003L;
        Long otherTeamId = 970004L;
        seedPublicTeam(teamId);
        Long slotOfOther = seedSlot(otherTeamId, FUTURE, LocalTime.of(12, 0), SlotStatus.FULL, 1, 1);

        assertThatThrownBy(() -> waitlistService.countWaiting(teamId, slotOfOther))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.SLOT_NOT_FOUND);
    }

    // ────────────────────────────────────────────────────────────
    // W-2 通知
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("W-2: 空き復帰で WAITING 全員に通知行が作られ、60 分以内は再送しない")
    void 空き復帰通知と再送抑制のDB観測() {
        Long teamId = 970005L;
        seedPublicTeam(teamId);
        // 通知は AVAILABLE 状態を再確認するため空き枠として観測する。
        Long slotId = seedSlot(teamId, FUTURE, LocalTime.of(13, 0), SlotStatus.AVAILABLE, 3, 0);
        Long u1 = 970501L;
        Long u2 = 970502L;
        waitlistRepository.save(ReservationWaitlistEntryEntity.builder()
                .teamId(teamId).slotId(slotId).userId(u1).status(WaitlistStatus.WAITING).build());
        waitlistRepository.save(ReservationWaitlistEntryEntity.builder()
                .teamId(teamId).slotId(slotId).userId(u2).status(WaitlistStatus.WAITING).build());

        waitlistService.notifySlotReopened(teamId, slotId);

        assertThat(notificationRepository.countBySourceTypeAndSourceId("RESERVATION", slotId)).isEqualTo(2L);
        waitlistRepository.findBySlotIdAndStatus(slotId, WaitlistStatus.WAITING)
                .forEach(e -> assertThat(e.getNotifiedAt()).isNotNull());

        // 直後の再通知は 60 分抑制で 0 件追加（合計 2 のまま）
        waitlistService.notifySlotReopened(teamId, slotId);
        assertThat(notificationRepository.countBySourceTypeAndSourceId("RESERVATION", slotId)).isEqualTo(2L);
    }

    @Test
    @DisplayName("W-2(枠単位重複なし): 別枠の待機者は当該枠の通知に混ざらない")
    void 枠単位で重複なく通知() {
        Long teamId = 970006L;
        seedPublicTeam(teamId);
        Long slotX = seedSlot(teamId, FUTURE, LocalTime.of(14, 0), SlotStatus.AVAILABLE, 2, 0);
        Long slotY = seedSlot(teamId, FUTURE, LocalTime.of(15, 0), SlotStatus.AVAILABLE, 2, 0);
        waitlistRepository.save(ReservationWaitlistEntryEntity.builder()
                .teamId(teamId).slotId(slotX).userId(970601L).status(WaitlistStatus.WAITING).build());
        waitlistRepository.save(ReservationWaitlistEntryEntity.builder()
                .teamId(teamId).slotId(slotY).userId(970602L).status(WaitlistStatus.WAITING).build());

        waitlistService.notifySlotReopened(teamId, slotX);

        assertThat(notificationRepository.countBySourceTypeAndSourceId("RESERVATION", slotX)).isEqualTo(1L);
        assertThat(notificationRepository.countBySourceTypeAndSourceId("RESERVATION", slotY)).isZero();
    }

    @Test
    @DisplayName("W-2(CONVERTED): 予約成立で同一 (slot, user) の WAITING が CONVERTED になる")
    void 予約成立でCONVERTED() {
        Long teamId = 970007L;
        seedPublicTeam(teamId);
        // reservations.line_id は NOT NULL のため予約対象ラインをシードし、リクエストに指定する。
        Long lineId = lineRepository.save(ReservationLineEntity.builder()
                .teamId(teamId).name("席1").isActive(true).build()).getId();
        // capacity 1・他ユーザーが予約して FULL 化 → A が待ち登録。
        Long slotId = seedSlot(teamId, FUTURE, LocalTime.of(16, 0), SlotStatus.AVAILABLE, 1, 0);
        Long booker = 970701L;
        Long waiterA = 970702L;

        var created = reservationService.createReservation(teamId, booker,
                new CreateReservationRequest(slotId, lineId, null));
        assertThat(slotRepository.findById(slotId).orElseThrow().getSlotStatus()).isEqualTo(SlotStatus.FULL);

        waitlistService.register(teamId, slotId, waiterA);
        assertThat(waitlistRepository.existsBySlotIdAndUserIdAndStatus(slotId, waiterA, WaitlistStatus.WAITING)).isTrue();

        // booker がキャンセル → 枠が AVAILABLE、次に A が予約すると A の WAITING が CONVERTED になる。
        reservationService.cancelByAdmin(teamId, created.getId(),
                new com.mannschaft.app.reservation.dto.CancelReservationRequest("空いた"));
        assertThat(slotRepository.findById(slotId).orElseThrow().getSlotStatus()).isEqualTo(SlotStatus.AVAILABLE);

        reservationService.createReservation(teamId, waiterA, new CreateReservationRequest(slotId, lineId, null));
        assertThat(waitlistRepository.existsBySlotIdAndUserIdAndStatus(slotId, waiterA, WaitlistStatus.WAITING)).isFalse();
        assertThat(waitlistRepository.findByUserIdAndStatusOrderByCreatedAtDesc(waiterA, WaitlistStatus.CONVERTED))
                .hasSize(1);
    }

    // ────────────────────────────────────────────────────────────
    // 自動失効
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("自動失効: 枠開始を過ぎた WAITING が物理削除され、未来枠は残る")
    void 失効クリーンアップのDB観測() {
        Long teamId = 970008L;
        seedPublicTeam(teamId);
        Long pastSlot = seedSlot(teamId, LocalDate.now().minusDays(1), LocalTime.of(10, 0), SlotStatus.FULL, 1, 1);
        Long futureSlot = seedSlot(teamId, FUTURE, LocalTime.of(10, 0), SlotStatus.FULL, 1, 1);
        Long userId = 970801L;
        // register() は過去枠を弾くため、失効対象は直接シードする。
        waitlistRepository.save(ReservationWaitlistEntryEntity.builder()
                .teamId(teamId).slotId(pastSlot).userId(userId).status(WaitlistStatus.WAITING).build());
        waitlistService.register(teamId, futureSlot, userId);

        int purged = waitlistService.purgeExpiredWaiting();

        assertThat(purged).isGreaterThanOrEqualTo(1);
        assertThat(waitlistRepository.countBySlotIdAndStatus(pastSlot, WaitlistStatus.WAITING)).isZero();
        assertThat(waitlistRepository.countBySlotIdAndStatus(futureSlot, WaitlistStatus.WAITING)).isEqualTo(1L);
    }
}
