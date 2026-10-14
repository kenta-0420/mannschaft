package com.mannschaft.app.ticket;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.ticket.entity.TicketBookEntity;
import com.mannschaft.app.ticket.event.TicketExpiryWarningNotificationEvent;
import com.mannschaft.app.ticket.repository.TicketBookRepository;
import com.mannschaft.app.ticket.service.TicketExpiryBatchService;
import com.mannschaft.app.ticket.service.TicketExpiryRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link TicketExpiryBatchService} の単体テスト。
 *
 * <p>Issue #2997（CMP-260827-1152 G6）: 通知は業務TXの外（{@code AFTER_COMMIT} リスナー）へ移ったため、
 * 本クラスは「期限切れ遷移を 1 冊ずつ項目TX（{@link TicketExpiryRunner}）へ委ねること」と
 * 「事前通知が暦日窓で抽出して配送要求を publish すること」を固定する。配送の意味論（文面・locale）は
 * {@code TicketExpiryNotificationListenerTest}、TX 境界は {@code TicketExpiryNotificationTransactionIT} が持つ。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TicketExpiryBatchService 単体テスト")
class TicketExpiryBatchServiceTest {

    private static final LocalDateTime CURRENT_TIME = LocalDateTime.of(2026, 4, 1, 9, 0);
    private static final LocalDate TODAY = CURRENT_TIME.toLocalDate();
    private static final Instant CURRENT_INSTANT = CURRENT_TIME
            .atZone(UserZoneLocalDateTimeParser.SERVER_ZONE)
            .toInstant();

    @Mock private TicketBookRepository bookRepository;
    @Mock private TicketExpiryRunner expiryRunner;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private Clock wallClock;

    @InjectMocks
    private TicketExpiryBatchService service;

    @BeforeEach
    void setUpClock() {
        lenient().when(wallClock.instant()).thenReturn(CURRENT_INSTANT);
        lenient().when(wallClock.getZone()).thenReturn(UserZoneLocalDateTimeParser.SERVER_ZONE);
    }

    private TicketBookEntity book(Long id, Long userId) {
        TicketBookEntity book = TicketBookEntity.builder()
                .teamId(1L).userId(userId).totalTickets(10).build();
        ReflectionTestUtils.setField(book, "id", id);
        return book;
    }

    @Nested
    @DisplayName("expireTickets")
    class ExpireTickets {

        @Test
        @DisplayName("正常系: 期限切れチケットがない場合はスキップ")
        void 期限切れチケットなし() {
            given(bookRepository.findExpiredActiveBooks(any())).willReturn(List.of());

            service.expireTickets();

            verify(bookRepository).findExpiredActiveBooks(eq(CURRENT_TIME));
            verify(expiryRunner, never()).expireOne(anyLong(), any());
        }

        @Test
        @DisplayName("正常系: 期限切れチケットが 1 冊ずつ項目TXで処理される")
        void 期限切れチケット処理() {
            given(bookRepository.findExpiredActiveBooks(any()))
                    .willReturn(List.of(book(1L, 100L), book(2L, 200L)));

            service.expireTickets();

            verify(expiryRunner).expireOne(1L, CURRENT_TIME);
            verify(expiryRunner).expireOne(2L, CURRENT_TIME);
        }

        @Test
        @DisplayName("AC-B2: 1 冊の項目TXが例外でも、他の冊の処理は続行されバッチは例外を投げない")
        void 一冊の失敗が他の冊を巻き込まない() {
            given(bookRepository.findExpiredActiveBooks(any()))
                    .willReturn(List.of(book(1L, 100L), book(2L, 200L)));
            given(expiryRunner.expireOne(eq(1L), any())).willThrow(new RuntimeException("項目TX失敗"));

            service.expireTickets();

            verify(expiryRunner).expireOne(2L, CURRENT_TIME);
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

            verify(bookRepository).findStalePendingBooks(eq(CURRENT_TIME.minusHours(2)));
            verify(bookRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("sendExpiryNotifications")
    class SendExpiryNotifications {

        @Test
        @DisplayName("正常系: 30/7/3/1日前の各暦日区間でチケットを検索する")
        void 期限切れ事前通知_暦日区間で検索() {
            // Given: 全区間で空を返す（検索区間の正しさを検証する）
            given(bookRepository.findBooksExpiringBetween(any(), any())).willReturn(List.of());

            // When
            service.sendExpiryNotifications();

            // Then: 4 つの通知日ぶん検索される
            verify(bookRepository, times(4)).findBooksExpiringBetween(any(), any());

            // 30 日前区間が [today+30 00:00, today+31 00:00) であることを検証する
            LocalDateTime from30 = TODAY.plusDays(30).atStartOfDay();
            LocalDateTime to30 = TODAY.plusDays(31).atStartOfDay();
            verify(bookRepository).findBooksExpiringBetween(eq(from30), eq(to30));
            verify(eventPublisher, never()).publishEvent(any(TicketExpiryWarningNotificationEvent.class));
        }

        @Test
        @DisplayName("正常系: 該当チケットがある場合は所有者宛ての配送要求を日数つきで publish する")
        void 期限切れ事前通知_該当ありで配送要求() {
            // Given: 1 区間（30 日前）でのみ 1 件ヒットさせる
            given(bookRepository.findBooksExpiringBetween(any(), any()))
                    .willReturn(List.of(book(5L, 100L)), List.of(), List.of(), List.of());

            // When
            service.sendExpiryNotifications();

            // Then
            ArgumentCaptor<TicketExpiryWarningNotificationEvent> captor =
                    ArgumentCaptor.forClass(TicketExpiryWarningNotificationEvent.class);
            verify(eventPublisher, times(1)).publishEvent(captor.capture());
            assertThat(captor.getValue().bookId()).isEqualTo(5L);
            assertThat(captor.getValue().userId()).isEqualTo(100L);
            assertThat(captor.getValue().daysBefore()).isEqualTo(30);
        }
    }
}
