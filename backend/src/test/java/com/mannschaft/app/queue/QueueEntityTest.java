package com.mannschaft.app.queue;

import com.mannschaft.app.queue.entity.QueueCounterEntity;
import com.mannschaft.app.queue.entity.QueueTicketEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link QueueTicketEntity} および {@link QueueCounterEntity} のエンティティ単体テスト。
 * JaCoCoでカバーされていないメソッドを補完する。
 */
@DisplayName("Queueエンティティ 単体テスト")
class QueueEntityTest {

    // ========================================
    // QueueTicketEntity
    // ========================================

    @Nested
    @DisplayName("QueueTicketEntity")
    class QueueTicketEntityTests {

        private QueueTicketEntity createTicket() {
            return QueueTicketEntity.builder()
                    .categoryId(1L)
                    .counterId(10L)
                    .ticketNumber("Q001")
                    .userId(100L)
                    .partySize((short) 1)
                    .source(TicketSource.ONLINE)
                    .status(TicketStatus.WAITING)
                    .position(1)
                    .issuedDate(LocalDate.now())
                    .build();
        }

        @Test
        @DisplayName("updatePosition_新しいポジション設定_値が更新される")
        void updatePosition_新しいポジション設定_値が更新される() {
            // Given
            QueueTicketEntity ticket = createTicket();

            // When
            ticket.updatePosition(5);

            // Then
            assertThat(ticket.getPosition()).isEqualTo(5);
        }

        @Test
        @DisplayName("updateEstimatedWaitMinutes_推定待ち時間更新_値が更新される")
        void updateEstimatedWaitMinutes_推定待ち時間更新_値が更新される() {
            // Given
            QueueTicketEntity ticket = createTicket();

            // When
            ticket.updateEstimatedWaitMinutes((short) 30);

            // Then
            assertThat(ticket.getEstimatedWaitMinutes()).isEqualTo((short) 30);
        }

        @Test
        @DisplayName("call_WAITING状態から呼び出し_CALLED状態になる")
        void call_WAITING状態から呼び出し_CALLED状態になる() {
            // Given
            QueueTicketEntity ticket = createTicket();

            // When
            ticket.call();

            // Then
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.CALLED);
            assertThat(ticket.getCalledAt()).isNotNull();
        }

        @Test
        @DisplayName("startServing_CALLED状態から対応開始_SERVING状態になる")
        void startServing_CALLED状態から対応開始_SERVING状態になる() {
            // Given
            QueueTicketEntity ticket = createTicket();
            ticket.call();

            // When
            ticket.startServing();

            // Then
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.SERVING);
            assertThat(ticket.getServingAt()).isNotNull();
        }

        @Test
        @DisplayName("complete_SERVING状態から完了_COMPLETED状態になる")
        void complete_SERVING状態から完了_COMPLETED状態になる() {
            // Given
            QueueTicketEntity ticket = createTicket();
            ticket.call();
            ticket.startServing();

            // When
            ticket.complete((short) 15);

            // Then
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.COMPLETED);
            assertThat(ticket.getCompletedAt()).isNotNull();
            assertThat(ticket.getActualServiceMinutes()).isEqualTo((short) 15);
        }

        @Test
        @DisplayName("cancel_キャンセル_CANCELLED状態になる")
        void cancel_キャンセル_CANCELLED状態になる() {
            // Given
            QueueTicketEntity ticket = createTicket();

            // When
            ticket.cancel(200L);

            // Then
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.CANCELLED);
            assertThat(ticket.getCancelledAt()).isNotNull();
            assertThat(ticket.getCancelledBy()).isEqualTo(200L);
        }

        @Test
        @DisplayName("markNoShow_不在マーク_NO_SHOW状態になる")
        void markNoShow_不在マーク_NO_SHOW状態になる() {
            // Given
            QueueTicketEntity ticket = createTicket();
            ticket.call();

            // When
            ticket.markNoShow();

            // Then
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.NO_SHOW);
            assertThat(ticket.getNoShowAt()).isNotNull();
        }

        @Test
        @DisplayName("hold_保留設定_holdUsedがtrueになりWAITING状態を維持")
        void hold_保留設定_holdUsedがtrueになりWAITING状態を維持() {
            // Given
            QueueTicketEntity ticket = createTicket();
            ticket.call();
            LocalDateTime holdUntil = LocalDateTime.now().plusMinutes(10);

            // When
            ticket.hold(holdUntil);

            // Then
            assertThat(ticket.getHoldUsed()).isTrue();
            assertThat(ticket.getHoldUntil()).isEqualTo(holdUntil);
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.WAITING);
        }
    }

    // ========================================
    // QueueCounterEntity
    // ========================================

    @Nested
    @DisplayName("QueueCounterEntity")
    class QueueCounterEntityTests {

        private QueueCounterEntity createCounter() {
            return QueueCounterEntity.builder()
                    .categoryId(1L)
                    .name("窓口1")
                    .avgServiceMinutes((short) 10)
                    .maxQueueSize((short) 50)
                    .isActive(true)
                    .isAccepting(true)
                    .build();
        }

        @Test
        @DisplayName("toggleAccepting_受付停止_isAcceptingがfalseになる")
        void toggleAccepting_受付停止_isAcceptingがfalseになる() {
            // Given
            QueueCounterEntity counter = createCounter();

            // When
            counter.toggleAccepting(false);

            // Then
            assertThat(counter.getIsAccepting()).isFalse();
        }

        @Test
        @DisplayName("toggleAccepting_受付再開_isAcceptingがtrueになる")
        void toggleAccepting_受付再開_isAcceptingがtrueになる() {
            // Given
            QueueCounterEntity counter = QueueCounterEntity.builder()
                    .categoryId(1L)
                    .name("窓口1")
                    .avgServiceMinutes((short) 10)
                    .maxQueueSize((short) 50)
                    .isActive(true)
                    .isAccepting(false)
                    .build();

            // When
            counter.toggleAccepting(true);

            // Then
            assertThat(counter.getIsAccepting()).isTrue();
        }

        @Test
        @DisplayName("softDelete_論理削除_deletedAtが設定される")
        void softDelete_論理削除_deletedAtが設定される() {
            // Given
            QueueCounterEntity counter = createCounter();

            // When
            counter.softDelete();

            // Then
            assertThat(counter.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("update_全フィールド更新_値が反映される")
        void update_全フィールド更新_値が反映される() {
            // Given
            QueueCounterEntity counter = createCounter();
            LocalTime from = LocalTime.of(9, 0);
            LocalTime to = LocalTime.of(18, 0);

            // When
            counter.update("新窓口", "説明", AcceptMode.QR_ONLY,
                    (short) 15, true, (short) 30, false, false,
                    from, to, (short) 2);

            // Then
            assertThat(counter.getName()).isEqualTo("新窓口");
            assertThat(counter.getDescription()).isEqualTo("説明");
            assertThat(counter.getAcceptMode()).isEqualTo(AcceptMode.QR_ONLY);
            assertThat(counter.getAvgServiceMinutes()).isEqualTo((short) 15);
            assertThat(counter.getMaxQueueSize()).isEqualTo((short) 30);
            assertThat(counter.getIsActive()).isFalse();
            assertThat(counter.getIsAccepting()).isFalse();
            assertThat(counter.getOperatingTimeFrom()).isEqualTo(from);
            assertThat(counter.getOperatingTimeTo()).isEqualTo(to);
            assertThat(counter.getDisplayOrder()).isEqualTo((short) 2);
        }
    }
}
