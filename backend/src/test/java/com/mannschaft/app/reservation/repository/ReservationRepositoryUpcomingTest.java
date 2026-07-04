package com.mannschaft.app.reservation.repository;

import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.SlotStatus;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReservationRepository#findUpcomingByUserId} 番人テスト。
 *
 * <p>「直近予約（upcoming）」は<strong>申込時刻（{@code booked_at}）ではなく
 * 来店日時（予約枠の {@code slot_date} ＋ {@code start_time}）</strong>で判定されることを検証する。
 * 旧実装（{@code status='CONFIRMED' AND booked_at >= now}）では「過去に申し込んだ未来枠」が
 * 直近予約として拾えず、ほぼ常に空を返すバグがあった（実機E2Eで発見）。</p>
 *
 * <p>検証観点:</p>
 * <ul>
 *   <li>過去に申込（{@code booked_at} = 過去）した未来枠の CONFIRMED 予約は upcoming に含まれる</li>
 *   <li>来店日時が過去の枠は含まれない</li>
 *   <li>キャンセル / 完了 / no-show は含まれない</li>
 *   <li>枠開始ちょうど（{@code start_time == now}）は含まれる（下限は閉区間）</li>
 *   <li>並び順は来店日時（日付→開始時刻）の昇順</li>
 *   <li>別ユーザーの予約は含まれない</li>
 * </ul>
 */
@Transactional
@DisplayName("ReservationRepository#findUpcomingByUserId 番人テスト（来店日時基準）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReservationRepositoryUpcomingTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ReservationRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long USER_ID = 5001L;
    private static final Long OTHER_USER_ID = 5002L;
    private static final Long TEAM_ID = 9001L;
    private static final Long LINE_ID = 8001L;

    /** 「現在」= 2026-04-01 10:00 を固定基準とする。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 1);
    private static final LocalTime NOW_TIME = LocalTime.of(10, 0);

    /** 「過去の申込時刻」（旧実装ならこの予約は upcoming から漏れる）。 */
    private static final LocalDateTime PAST_BOOKED_AT = LocalDateTime.of(2026, 1, 1, 12, 0);

    /** 予約枠を永続化して ID を返す。 */
    private Long persistSlot(LocalDate slotDate, LocalTime startTime) {
        ReservationSlotEntity slot = ReservationSlotEntity.builder()
                .teamId(TEAM_ID)
                .slotDate(slotDate)
                .startTime(startTime)
                .endTime(startTime.plusHours(1))
                .slotStatus(SlotStatus.AVAILABLE)
                .build();
        em.persist(slot);
        em.flush();
        return slot.getId();
    }

    /** 予約を永続化する。 */
    private ReservationEntity persistReservation(
            Long userId, Long slotId, ReservationStatus status, LocalDateTime bookedAt) {
        ReservationEntity reservation = ReservationEntity.builder()
                .reservationSlotId(slotId)
                .lineId(LINE_ID)
                .teamId(TEAM_ID)
                .userId(userId)
                .status(status)
                .bookedAt(bookedAt)
                .build();
        em.persist(reservation);
        em.flush();
        return reservation;
    }

    private List<ReservationEntity> findUpcoming() {
        em.clear();
        return repository.findUpcomingByUserId(USER_ID, TODAY, NOW_TIME);
    }

    @Nested
    @DisplayName("来店日時基準の絞り込み")
    class SlotDateTimeFilter {

        @Test
        @DisplayName("過去に申し込んだ未来枠の CONFIRMED 予約は upcoming に含まれる（バグ根治の要）")
        void 過去申込_未来枠_CONFIRMEDは含まれる() {
            // Given: 申込は過去だが来店日時は未来（翌日 09:00）
            Long slotId = persistSlot(TODAY.plusDays(1), LocalTime.of(9, 0));
            persistReservation(USER_ID, slotId, ReservationStatus.CONFIRMED, PAST_BOOKED_AT);

            // When
            List<ReservationEntity> result = findUpcoming();

            // Then: 旧実装（booked_at >= now）なら空で落ちる red
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getReservationSlotId()).isEqualTo(slotId);
        }

        @Test
        @DisplayName("来店日時が過去の枠は含まれない")
        void 過去枠は含まれない() {
            // Given: 来店日時が過去（前日）。申込は未来でも来店済み扱い
            Long slotId = persistSlot(TODAY.minusDays(1), LocalTime.of(9, 0));
            persistReservation(USER_ID, slotId, ReservationStatus.CONFIRMED, PAST_BOOKED_AT);

            // When & Then
            assertThat(findUpcoming()).isEmpty();
        }

        @Test
        @DisplayName("同日で開始時刻が現在より前の枠は含まれない")
        void 同日_開始時刻が過去の枠は含まれない() {
            // Given: 本日 09:59（現在 10:00 の直前）
            Long slotId = persistSlot(TODAY, LocalTime.of(9, 59));
            persistReservation(USER_ID, slotId, ReservationStatus.CONFIRMED, PAST_BOOKED_AT);

            // When & Then
            assertThat(findUpcoming()).isEmpty();
        }

        @Test
        @DisplayName("枠開始ちょうど（開始時刻 == 現在）は含まれる（下限は閉区間）")
        void 枠開始ちょうど_境界は含まれる() {
            // Given: 本日 10:00（現在ちょうど）
            Long slotId = persistSlot(TODAY, NOW_TIME);
            persistReservation(USER_ID, slotId, ReservationStatus.CONFIRMED, PAST_BOOKED_AT);

            // When & Then
            assertThat(findUpcoming()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("ステータス絞り込み")
    class StatusFilter {

        @Test
        @DisplayName("CANCELLED / COMPLETED / NO_SHOW / PENDING は未来枠でも含まれない")
        void 無効ステータスは含まれない() {
            // Given: いずれも未来枠だが CONFIRMED 以外
            persistReservation(USER_ID, persistSlot(TODAY.plusDays(1), LocalTime.of(9, 0)),
                    ReservationStatus.CANCELLED, PAST_BOOKED_AT);
            persistReservation(USER_ID, persistSlot(TODAY.plusDays(1), LocalTime.of(10, 0)),
                    ReservationStatus.COMPLETED, PAST_BOOKED_AT);
            persistReservation(USER_ID, persistSlot(TODAY.plusDays(1), LocalTime.of(11, 0)),
                    ReservationStatus.NO_SHOW, PAST_BOOKED_AT);
            persistReservation(USER_ID, persistSlot(TODAY.plusDays(1), LocalTime.of(12, 0)),
                    ReservationStatus.PENDING, PAST_BOOKED_AT);

            // When & Then
            assertThat(findUpcoming()).isEmpty();
        }
    }

    @Nested
    @DisplayName("並び順・ユーザー分離")
    class OrderingAndUserScope {

        @Test
        @DisplayName("来店日時（日付→開始時刻）の昇順で返る")
        void 来店日時昇順で返る() {
            // Given: 登録順とは逆順に来店日時が並ぶよう投入
            Long later = persistSlot(TODAY.plusDays(2), LocalTime.of(9, 0));
            Long sameDayLate = persistSlot(TODAY, LocalTime.of(15, 0));
            Long sameDayEarly = persistSlot(TODAY, LocalTime.of(11, 0));
            persistReservation(USER_ID, later, ReservationStatus.CONFIRMED, PAST_BOOKED_AT);
            persistReservation(USER_ID, sameDayLate, ReservationStatus.CONFIRMED, PAST_BOOKED_AT);
            persistReservation(USER_ID, sameDayEarly, ReservationStatus.CONFIRMED, PAST_BOOKED_AT);

            // When
            List<ReservationEntity> result = findUpcoming();

            // Then: 本日11:00 → 本日15:00 → 翌々日09:00
            assertThat(result).extracting(ReservationEntity::getReservationSlotId)
                    .containsExactly(sameDayEarly, sameDayLate, later);
        }

        @Test
        @DisplayName("別ユーザーの予約は含まれない")
        void 別ユーザーは含まれない() {
            // Given: 別ユーザーの未来枠 CONFIRMED
            Long slotId = persistSlot(TODAY.plusDays(1), LocalTime.of(9, 0));
            persistReservation(OTHER_USER_ID, slotId, ReservationStatus.CONFIRMED, PAST_BOOKED_AT);

            // When & Then
            assertThat(findUpcoming()).isEmpty();
        }
    }
}
