package com.mannschaft.app.ticket;

import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.ticket.entity.TicketBookEntity;
import com.mannschaft.app.ticket.repository.TicketBookRepository;
import com.mannschaft.app.ticket.service.TicketExpiryBatchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link TicketExpiryBatchService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TicketExpiryBatchService 単体テスト")
class TicketExpiryBatchServiceTest {

    @Mock private TicketBookRepository bookRepository;
    @Mock private NotificationHelper notificationHelper;

    @InjectMocks
    private TicketExpiryBatchService service;

    @Nested
    @DisplayName("expireTickets")
    class ExpireTickets {

        @Test
        @DisplayName("正常系: 期限切れチケットがない場合はスキップ")
        void 期限切れチケットなし() {
            given(bookRepository.findExpiredActiveBooks(any())).willReturn(List.of());

            service.expireTickets();

            verify(bookRepository, never()).save(any());
        }

        @Test
        @DisplayName("正常系: 期限切れチケットが処理される")
        void 期限切れチケット処理() {
            TicketBookEntity book = TicketBookEntity.builder()
                    .teamId(1L).userId(100L).totalTickets(10).build();
            given(bookRepository.findExpiredActiveBooks(any())).willReturn(List.of(book));
            given(bookRepository.save(any())).willReturn(book);

            service.expireTickets();

            verify(bookRepository).save(any());
            verify(notificationHelper).notify(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("cleanupPendingBooks")
    class CleanupPendingBooks {

        @Test
        @DisplayName("正常系: PENDING チケットがない場合はスキップ")
        void PENDINGチケットなし() {
            given(bookRepository.findStalePendingBooks(any())).willReturn(List.of());

            service.cleanupPendingBooks();

            verify(bookRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("sendExpiryNotifications")
    class SendExpiryNotifications {

        @Test
        @DisplayName("正常系: 30/7/3/1日前の各暦日区間でチケットを検索し通知する")
        void 期限切れ事前通知_暦日区間で検索() {
            // Given: 全区間で空を返す（検索区間の正しさを検証する）
            given(bookRepository.findBooksExpiringBetween(any(), any())).willReturn(List.of());

            // When
            service.sendExpiryNotifications();

            // Then: 4 つの通知日ぶん検索される
            verify(bookRepository, times(4)).findBooksExpiringBetween(any(), any());

            // 30 日前区間が [today+30 00:00, today+31 00:00) であることを検証する
            LocalDate today = LocalDate.now();
            LocalDateTime from30 = today.plusDays(30).atStartOfDay();
            LocalDateTime to30 = today.plusDays(31).atStartOfDay();
            verify(bookRepository).findBooksExpiringBetween(eq(from30), eq(to30));
            verify(notificationHelper, never())
                    .notify(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("正常系: 該当チケットがある場合は通知を送る")
        void 期限切れ事前通知_該当ありで通知() {
            // Given: 1 区間でのみ 1 件ヒットさせる
            TicketBookEntity book = TicketBookEntity.builder()
                    .teamId(1L).userId(100L).totalTickets(10).build();
            given(bookRepository.findBooksExpiringBetween(any(), any()))
                    .willReturn(List.of(book), List.of(), List.of(), List.of());

            // When
            service.sendExpiryNotifications();

            // Then
            verify(notificationHelper, times(1))
                    .notify(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
            assertThat(book).isNotNull();
        }
    }
}
