package com.mannschaft.app.ticket;

import com.mannschaft.app.ticket.entity.TicketBookEntity;
import com.mannschaft.app.ticket.event.TicketExpiredNotificationEvent;
import com.mannschaft.app.ticket.repository.TicketBookRepository;
import com.mannschaft.app.ticket.service.TicketExpiryRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link TicketExpiryRunner} のユニットテスト（Issue #2997 / CMP-260827-1152 G6）。
 *
 * <p>EXPIRED 遷移の確定と配送要求の publish が 1 冊単位で完結し、再実行しても二重に遷移・二重に publish しない
 * （冪等。AC-D: 印は status であり、EXPIRED になった冊は再抽出されない）ことを固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TicketExpiryRunner 単体テスト")
class TicketExpiryRunnerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 4, 1, 0, 30);

    @Mock private TicketBookRepository bookRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private TicketExpiryRunner runner;

    private TicketBookEntity book(TicketBookStatus status, LocalDateTime expiresAt) {
        TicketBookEntity book = TicketBookEntity.builder()
                .teamId(1L).userId(100L).totalTickets(10).status(status).expiresAt(expiresAt).build();
        ReflectionTestUtils.setField(book, "id", 7L);
        return book;
    }

    @Test
    @DisplayName("期限超過の ACTIVE は EXPIRED に遷移して保存され、所有者宛ての配送要求が publish される")
    void expiresAndPublishes() {
        TicketBookEntity book = book(TicketBookStatus.ACTIVE, NOW.minusDays(1));
        given(bookRepository.findById(7L)).willReturn(Optional.of(book));

        assertThat(runner.expireOne(7L, NOW)).isTrue();

        assertThat(book.getStatus()).isEqualTo(TicketBookStatus.EXPIRED);
        verify(bookRepository).save(book);
        ArgumentCaptor<TicketExpiredNotificationEvent> captor =
                ArgumentCaptor.forClass(TicketExpiredNotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().bookId()).isEqualTo(7L);
        assertThat(captor.getValue().userId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("抽出後に ACTIVE でなくなっていたら何もせず false（冪等）")
    void notActiveIsNoop() {
        TicketBookEntity book = book(TicketBookStatus.EXPIRED, NOW.minusDays(1));
        given(bookRepository.findById(7L)).willReturn(Optional.of(book));

        assertThat(runner.expireOne(7L, NOW)).isFalse();

        verify(bookRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(TicketExpiredNotificationEvent.class));
    }

    @Test
    @DisplayName("抽出後に期限が延長されていたら何もせず false")
    void extendedIsNoop() {
        TicketBookEntity book = book(TicketBookStatus.ACTIVE, NOW.plusDays(30));
        given(bookRepository.findById(7L)).willReturn(Optional.of(book));

        assertThat(runner.expireOne(7L, NOW)).isFalse();

        assertThat(book.getStatus()).isEqualTo(TicketBookStatus.ACTIVE);
        verify(bookRepository, never()).save(any());
    }

    @Test
    @DisplayName("冊が存在しなければ何もせず false")
    void missingIsNoop() {
        given(bookRepository.findById(7L)).willReturn(Optional.empty());

        assertThat(runner.expireOne(7L, NOW)).isFalse();

        verify(bookRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(TicketExpiredNotificationEvent.class));
    }
}
