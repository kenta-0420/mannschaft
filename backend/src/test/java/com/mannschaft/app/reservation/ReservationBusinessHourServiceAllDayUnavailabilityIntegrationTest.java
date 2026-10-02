package com.mannschaft.app.reservation;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.dto.BlockedTimeImpactResponse;
import com.mannschaft.app.reservation.dto.BlockedTimeRequest;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.service.ReservationBusinessHourService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 予約不可枠（機能B）<b>全日ブロック</b>の 409 ガード／impact プレビューの<b>実 MySQL 永続化結合テスト</b>
 * （実機E2E発見バグ・全日休業impact根治・AC-B1/AC-B2）。
 *
 * <h2>バグの背景</h2>
 * <p>{@link ReservationBusinessHourService#getBlockedTimeImpact} / {@code createBlockedTime} は
 * 全日ブロック（start/end 両 {@code null}）を {@code [LocalTime.MIN, LocalTime.MAX]} に展開し、
 * {@link ReservationRepository#findActiveReservationsOverlappingUnavailability} の半開区間
 * overlap 判定（{@code s.startTime < :endTimeExclusive}）に渡していた。
 * {@code LocalTime.MAX}（23:59:59.999999999）は MySQL の {@code TIME} 型カラム精度で
 * <b>丸められる</b>ため、JPQL 上は正しく見えても実 DB では overlap 判定が破綻し、
 * 全日ブロックの impact/409 ガードが active 予約を検出できない（affectedCount が常に 0）。</p>
 *
 * <h2>既存モック単体テストがこのバグを検出できない理由</h2>
 * <p>{@link ReservationBusinessHourServiceTest} の {@code B6_409ガード} はモックが
 * {@code findActiveReservationsOverlappingUnavailability(..., LocalTime.MIN, LocalTime.MAX, ...)}
 * を受けてそのまま予約を返す設定になっており、MySQL の {@code TIME} 型精度丸めを一切再現しない。
 * <b>このバグは実 MySQL に対する結合テストでしか捕捉できない</b>ため、本クラスを新設する。</p>
 *
 * <p>Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@Transactional
@DisplayName("ReservationBusinessHourService 全日ブロック 実MySQL結合テスト（全日休業impact根治・AC-B1/AC-B2）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReservationBusinessHourServiceAllDayUnavailabilityIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ReservationBusinessHourService service;

    @Autowired
    private ReservationSlotRepository slotRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationLineRepository lineRepository;

    private static final Long TEAM_ID = 990001L;
    private static final Long CREATED_BY = 990002L;
    private static final Long USER_ID = 990003L;
    private static final LocalDate BLOCKED_DATE = LocalDate.now().plusMonths(1);

    /** チームの枠（10:00-11:00）を 1 件作成する。 */
    private Long createSlot() {
        ReservationLineEntity line = lineRepository.save(ReservationLineEntity.builder()
                .teamId(TEAM_ID)
                .name("全日休業結合テスト用ライン")
                .isActive(true)
                .build());
        return slotRepository.save(ReservationSlotEntity.builder()
                .teamId(TEAM_ID)
                .lineId(line.getId())
                .title("全日休業結合テスト用枠")
                .slotDate(BLOCKED_DATE)
                .startTime(LocalTime.of(10, 0))
                .endTime(LocalTime.of(11, 0))
                .capacity(1)
                .build()).getId();
    }

    /** 指定枠に active（CONFIRMED）予約を 1 件作成する。 */
    private void createActiveReservation(Long slotId, Long lineId) {
        reservationRepository.save(ReservationEntity.builder()
                .reservationSlotId(slotId)
                .lineId(lineId)
                .teamId(TEAM_ID)
                .userId(USER_ID)
                .status(ReservationStatus.CONFIRMED)
                .build());
    }

    @Test
    @DisplayName("AC-B1: 全日(start/end 両null)impactは実MySQLでactive予約を検出する（affectedCount>=1）")
    void 全日impactは実MySQLでactive予約を検出する() {
        // Given: 対象日に CONFIRMED 予約が 1 件ある枠
        ReservationLineEntity line = lineRepository.save(ReservationLineEntity.builder()
                .teamId(TEAM_ID).name("AC-B1ライン").isActive(true).build());
        Long slotId = slotRepository.save(ReservationSlotEntity.builder()
                .teamId(TEAM_ID).lineId(line.getId()).title("AC-B1枠")
                .slotDate(BLOCKED_DATE).startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(11, 0))
                .capacity(1).build()).getId();
        createActiveReservation(slotId, line.getId());

        // When: 全日（start=null, end=null）で impact を問い合わせる
        BlockedTimeImpactResponse result = service.getBlockedTimeImpact(
                TEAM_ID, BLOCKED_DATE, ReservationBlockedResourceType.TEAM, null, null, null);

        // Then: LocalTime.MAX の MySQL TIME 型丸めに阻まれず、active 予約を検出できていること
        assertThat(result.getAffectedCount())
                .as("全日ブロックのimpactは実DBでその日のactive予約を検出できること（根治の本丸）")
                .isEqualTo(1);
        assertThat(result.getReservations()).hasSize(1);
    }

    @Test
    @DisplayName("AC-B2: 全日ブロックのPOSTはactive予約が残る日には409(UNAVAILABILITY_HAS_ACTIVE_RESERVATIONS)で拒否される")
    void 全日ブロック作成はactive予約が残る日には409で拒否される() {
        // Given: 対象日に CONFIRMED 予約が 1 件ある枠
        Long slotId = createSlot();
        ReservationSlotEntity slot = slotRepository.findById(slotId).orElseThrow();
        createActiveReservation(slotId, slot.getLineId());

        BlockedTimeRequest request = new BlockedTimeRequest(
                BLOCKED_DATE, null, null, "全日休業結合テスト", null, null);

        // When / Then: 全日ブロックの登録は 409 で拒否される（実MySQLでのガード）
        assertThatThrownBy(() -> service.createBlockedTime(TEAM_ID, request, CREATED_BY))
                .as("全日ブロックのPOSTはactive予約を実DBで検出し409拒否すること（根治の本丸）")
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.UNAVAILABILITY_HAS_ACTIVE_RESERVATIONS);
    }

    @Test
    @DisplayName("AC-B3回帰: 時間帯部分ブロック(start/end指定あり)のoverlap判定は実MySQLでも従来どおり検出する")
    void 部分ブロックのimpactは実MySQLで従来どおり検出する() {
        // Given: 10:00-11:00 の枠に CONFIRMED 予約
        Long slotId = createSlot();
        ReservationSlotEntity slot = slotRepository.findById(slotId).orElseThrow();
        createActiveReservation(slotId, slot.getLineId());

        // When: 重なる部分ブロック（10:30-10:45）で impact を問い合わせる
        BlockedTimeImpactResponse overlapping = service.getBlockedTimeImpact(
                TEAM_ID, BLOCKED_DATE, ReservationBlockedResourceType.TEAM, null,
                LocalTime.of(10, 30), LocalTime.of(10, 45));
        // When: 重ならない部分ブロック（12:00-13:00）で impact を問い合わせる
        BlockedTimeImpactResponse nonOverlapping = service.getBlockedTimeImpact(
                TEAM_ID, BLOCKED_DATE, ReservationBlockedResourceType.TEAM, null,
                LocalTime.of(12, 0), LocalTime.of(13, 0));

        // Then: 部分ブロックの overlap 判定は回帰なし（既存挙動維持）
        assertThat(overlapping.getAffectedCount()).as("重なる部分ブロックは検出").isEqualTo(1);
        assertThat(nonOverlapping.getAffectedCount()).as("重ならない部分ブロックは非検出").isEqualTo(0);
    }
}
