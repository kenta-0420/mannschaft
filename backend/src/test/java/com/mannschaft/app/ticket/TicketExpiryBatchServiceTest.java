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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
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
}
