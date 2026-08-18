package com.mannschaft.app.ticket;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.ticket.entity.TicketBookEntity;
import com.mannschaft.app.ticket.repository.TicketBookRepository;
import com.mannschaft.app.ticket.service.TicketExpiryBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
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
    @Mock private UserLocaleCache userLocaleCache;
    @Mock private MessageSource messageSource;

    @InjectMocks
    private TicketExpiryBatchService service;

    // Issue #2715 ロットB: 依存追加に伴う mock 漏れ対策。
    @BeforeEach
    void setUpLocaleStubs() {
        lenient().when(userLocaleCache.getLocales(any())).thenReturn(Map.of());
        lenient().when(userLocaleCache.getLocale(org.mockito.ArgumentMatchers.anyLong())).thenReturn("ja");
        lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(2));
    }

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

        @Test
        @DisplayName("Issue #2715 ロットB: 受信者 locale が en の場合、通知件名・本文が英語で組み立てられプレースホルダが残らない")
        void 受信者ロケールがenなら英語件名本文になる() {
            var realMessageSource = new org.springframework.context.support.ResourceBundleMessageSource();
            realMessageSource.setBasename("messages");
            realMessageSource.setDefaultEncoding("UTF-8");
            org.springframework.test.util.ReflectionTestUtils.setField(service, "messageSource", realMessageSource);

            TicketBookEntity book = TicketBookEntity.builder()
                    .teamId(1L).userId(200L).totalTickets(10).build();
            given(bookRepository.findExpiredActiveBooks(any())).willReturn(List.of(book));
            given(bookRepository.save(any())).willReturn(book);
            given(userLocaleCache.getLocales(any())).willReturn(Map.of(200L, "en"));

            service.expireTickets();

            org.mockito.ArgumentCaptor<String> titleCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            org.mockito.ArgumentCaptor<String> bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(notificationHelper).notify(
                    eq(200L), eq("TICKET_EXPIRED"), titleCaptor.capture(), bodyCaptor.capture(),
                    any(), any(), any(), any(), any(), any());
            assertThat(titleCaptor.getValue()).isEqualTo("Ticket expired");
            assertThat(bodyCaptor.getValue()).doesNotContain("{0}");
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

        @Test
        @DisplayName("Issue #2715 ロットB: locale=en で {0}(日数) が置換された英語本文になる")
        void locale_enで日数プレースホルダが置換される() {
            var realMessageSource = new org.springframework.context.support.ResourceBundleMessageSource();
            realMessageSource.setBasename("messages");
            realMessageSource.setDefaultEncoding("UTF-8");
            org.springframework.test.util.ReflectionTestUtils.setField(service, "messageSource", realMessageSource);

            TicketBookEntity book = TicketBookEntity.builder()
                    .teamId(1L).userId(300L).totalTickets(10).build();
            given(bookRepository.findBooksExpiringBetween(any(), any()))
                    .willReturn(List.of(book), List.of(), List.of(), List.of());
            given(userLocaleCache.getLocales(any())).willReturn(Map.of(300L, "en"));

            service.sendExpiryNotifications();

            org.mockito.ArgumentCaptor<String> bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(notificationHelper).notify(
                    eq(300L), eq("TICKET_EXPIRY_WARNING"), any(), bodyCaptor.capture(),
                    any(), any(), any(), any(), any(), any());
            assertThat(bodyCaptor.getValue()).doesNotContain("{0}").contains("30");
        }
    }
}
