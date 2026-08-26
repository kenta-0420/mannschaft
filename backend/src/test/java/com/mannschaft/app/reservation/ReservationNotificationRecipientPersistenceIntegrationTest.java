package com.mannschaft.app.reservation;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.mail.outbox.EmailOutboxEntity;
import com.mannschaft.app.mail.outbox.EmailOutboxRepository;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationNotificationRecipientEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.event.ReservationCreatedEvent;
import com.mannschaft.app.reservation.event.ReservationRecipientEmailEventListener;
import com.mannschaft.app.reservation.repository.ReservationNotificationRecipientRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 機能D 予約通知メール送出の<b>永続化</b>結合テスト（実 MySQL・§8 D-10 / D-9）。
 *
 * <h2>守る不変条件</h2>
 * <ul>
 *   <li><b>D-10</b>: 有効宛先 N 件に対し {@code email_outbox} が N 行生成される（1 宛先 = 1 outbox 行）。
 *       {@code EmailOutboxService.enqueue} は自前の {@code @Transactional} で書き込むため、
 *       リスナーからの enqueue が実 DB にコミットされることを実層で番人化する（純 Mockito UT では
 *       routing / 実 UNIQUE を踏まないため見逃す）。</li>
 *   <li><b>D-9</b>: 同一予約 × 同一 email の二重 enqueue は二重送出されない。
 *       観測点 {@code email_outbox.idempotency_key}（CHAR(32) UNIQUE）が同一値で 1 行のみ
 *       （2 回目 enqueue は EMAIL_OUTBOX_004 で弾かれ、リスナーの行単位 try/catch が握らず記録する）。</li>
 * </ul>
 *
 * <p>リスナーは {@code @Async} + {@code @TransactionalEventListener(AFTER_COMMIT)} だが、本テストは
 * スレッド非決定性を避けるため、実 Spring 管理の協調 Bean を注入した listener インスタンスを直接構築して
 * {@code onReservationCreated} を同期呼び出しする（AFTER_COMMIT/REQUIRES_NEW の起動時バリデーション健全性は
 * {@code ReservationRecipientEmailEventListenerContextTest} が別途番人化）。</p>
 *
 * <p>Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@DisplayName("機能D 予約通知メール送出 永続化結合テスト（実MySQL・outbox行生成/冪等）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReservationNotificationRecipientPersistenceIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ReservationNotificationRecipientRepository recipientRepository;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private ReservationSlotRepository slotRepository;
    @Autowired
    private NameResolverService nameResolverService;
    @Autowired
    private EmailOutboxService emailOutboxService;
    @Autowired
    private EmailOutboxRepository emailOutboxRepository;

    private ReservationRecipientEmailEventListener listener;

    private static final Long ACTOR_USER_ID = 987654L;

    @BeforeEach
    void setUp() {
        listener = new ReservationRecipientEmailEventListener(
                recipientRepository, reservationRepository, slotRepository,
                nameResolverService, emailOutboxService);
    }

    private Long uniqueTeamId() {
        // 並行・繰り返し実行で衝突しないよう実行時ユニークなチームIDを使う。
        return 900_000_000L + (System.nanoTime() % 90_000_000L);
    }

    private ReservationCreatedEvent seedAndBuildEvent(Long teamId, int enabledCount, int disabledCount) {
        for (int i = 0; i < enabledCount; i++) {
            recipientRepository.saveAndFlush(ReservationNotificationRecipientEntity.builder()
                    .teamId(teamId).email("on" + i + "-" + teamId + "@example.com")
                    .isEnabled(true).build());
        }
        for (int i = 0; i < disabledCount; i++) {
            recipientRepository.saveAndFlush(ReservationNotificationRecipientEntity.builder()
                    .teamId(teamId).email("off" + i + "-" + teamId + "@example.com")
                    .isEnabled(false).build());
        }
        ReservationSlotEntity slot = slotRepository.saveAndFlush(ReservationSlotEntity.builder()
                .teamId(teamId).slotDate(LocalDate.now().plusDays(3)).startTime(LocalTime.of(10, 0))
                .endTime(LocalTime.of(11, 0)).title("整体60分コース").build());
        ReservationEntity reservation = reservationRepository.saveAndFlush(ReservationEntity.builder()
                .teamId(teamId).userId(ACTOR_USER_ID).reservationSlotId(slot.getId())
                .lineId(1L).build());
        return new ReservationCreatedEvent(
                teamId, reservation.getId(), ACTOR_USER_ID, ApprovalMode.AUTO, slot.getTitle(), "2026/07/01 12:00");
    }

    private List<EmailOutboxEntity> outboxRowsFor(Long reservationId) {
        String prefix = "reservation-notify:" + reservationId + ":";
        return emailOutboxRepository.findAll().stream()
                .filter(r -> r.getSourceEventId() != null && r.getSourceEventId().startsWith(prefix))
                .toList();
    }

    @Test
    @DisplayName("D-10: 有効宛先3件+無効1件 → email_outbox に有効宛先分の3行のみ生成される")
    void 有効宛先分のoutbox行が生成される() {
        Long teamId = uniqueTeamId();
        ReservationCreatedEvent event = seedAndBuildEvent(teamId, 3, 1);

        listener.onReservationCreated(event);

        List<EmailOutboxEntity> rows = outboxRowsFor(event.getReservationId());
        assertThat(rows).hasSize(3);
        // 宛先ごとに異なる冪等キーが振られている。
        assertThat(rows).extracting(EmailOutboxEntity::getIdempotencyKey)
                .doesNotContainNull()
                .doesNotHaveDuplicates();
        assertThat(rows).allSatisfy(r ->
                assertThat(r.getTemplateKind()).isEqualTo("RESERVATION_RECEIVED_NOTIFY"));
    }

    @Test
    @DisplayName("D-9: 同一予約への二重発火でも同一 email の outbox 行は増えない（idempotency_key UNIQUE）")
    void 二重発火でも冪等で行が増えない() {
        Long teamId = uniqueTeamId();
        ReservationCreatedEvent event = seedAndBuildEvent(teamId, 2, 0);

        listener.onReservationCreated(event);
        // 2 回目: 同一予約×同一 email → 同一冪等キー → EMAIL_OUTBOX_004 で弾かれ、行単位 try/catch が握る。
        listener.onReservationCreated(event);

        List<EmailOutboxEntity> rows = outboxRowsFor(event.getReservationId());
        assertThat(rows).hasSize(2);
    }
}
