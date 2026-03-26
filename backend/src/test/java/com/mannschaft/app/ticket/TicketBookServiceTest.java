package com.mannschaft.app.ticket;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.ticket.dto.ConsumeTicketRequest;
import com.mannschaft.app.ticket.dto.ExtendRequest;
import com.mannschaft.app.ticket.dto.RefundRequest;
import com.mannschaft.app.ticket.entity.TicketBookEntity;
import com.mannschaft.app.ticket.entity.TicketConsumptionEntity;
import com.mannschaft.app.ticket.entity.TicketProductEntity;
import com.mannschaft.app.ticket.repository.TicketBookRepository;
import com.mannschaft.app.ticket.repository.TicketConsumptionRepository;
import com.mannschaft.app.ticket.repository.TicketPaymentRepository;
import com.mannschaft.app.ticket.repository.TicketProductRepository;
import com.mannschaft.app.ticket.service.StripeTicketService;
import com.mannschaft.app.ticket.service.TicketBookService;
import com.mannschaft.app.ticket.service.TicketQrService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link TicketBookService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TicketBookService 単体テスト")
class TicketBookServiceTest {

    @Mock private TicketBookRepository bookRepository;
    @Mock private TicketConsumptionRepository consumptionRepository;
    @Mock private TicketPaymentRepository paymentRepository;
    @Mock private TicketProductRepository productRepository;
    @Mock private StripeTicketService stripeTicketService;
    @Mock private TicketQrService ticketQrService;
    @Mock private TicketMapper ticketMapper;
    @Mock private NameResolverService nameResolverService;

    @InjectMocks
    private TicketBookService service;

    private static final Long TEAM_ID = 1L;
    private static final Long BOOK_ID = 10L;
    private static final Long STAFF_ID = 100L;

    @Nested
    @DisplayName("consumeTicket")
    class ConsumeTicket {

        @Test
        @DisplayName("異常系: チケットが見つからない場合エラー")
        void チケット不存在() {
            given(bookRepository.findByIdAndTeamIdForUpdate(BOOK_ID, TEAM_ID)).willReturn(Optional.empty());

            ConsumeTicketRequest request = new ConsumeTicketRequest(null, null, null, null);

            assertThatThrownBy(() -> service.consumeTicket(TEAM_ID, BOOK_ID, STAFF_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TicketErrorCode.BOOK_NOT_FOUND);
        }

        @Test
        @DisplayName("異常系: ACTIVE以外のチケット消化はエラー")
        void ACTIVE以外消化不可() {
            TicketBookEntity book = TicketBookEntity.builder()
                    .teamId(TEAM_ID).totalTickets(10).build();
            setStatus(book, TicketBookStatus.EXPIRED);
            given(bookRepository.findByIdAndTeamIdForUpdate(BOOK_ID, TEAM_ID)).willReturn(Optional.of(book));

            ConsumeTicketRequest request = new ConsumeTicketRequest(null, null, null, null);

            assertThatThrownBy(() -> service.consumeTicket(TEAM_ID, BOOK_ID, STAFF_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TicketErrorCode.TICKET_NOT_ACTIVE);
        }

        @Test
        @DisplayName("異常系: 消化日時が未来の場合エラー")
        void 消化日時未来() {
            TicketBookEntity book = TicketBookEntity.builder()
                    .teamId(TEAM_ID).totalTickets(10).build();
            setStatus(book, TicketBookStatus.ACTIVE);
            given(bookRepository.findByIdAndTeamIdForUpdate(BOOK_ID, TEAM_ID)).willReturn(Optional.of(book));

            ConsumeTicketRequest request = new ConsumeTicketRequest(
                    null, null, LocalDateTime.now().plusDays(1), null);

            assertThatThrownBy(() -> service.consumeTicket(TEAM_ID, BOOK_ID, STAFF_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TicketErrorCode.CONSUMED_AT_FUTURE);
        }

        @Test
        @DisplayName("異常系: 消化日時が72時間以上前の場合エラー")
        void 消化日時72時間超過() {
            TicketBookEntity book = TicketBookEntity.builder()
                    .teamId(TEAM_ID).totalTickets(10).build();
            setStatus(book, TicketBookStatus.ACTIVE);
            given(bookRepository.findByIdAndTeamIdForUpdate(BOOK_ID, TEAM_ID)).willReturn(Optional.of(book));

            ConsumeTicketRequest request = new ConsumeTicketRequest(
                    null, null, LocalDateTime.now().minusDays(4), null);

            assertThatThrownBy(() -> service.consumeTicket(TEAM_ID, BOOK_ID, STAFF_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TicketErrorCode.CONSUMED_AT_TOO_OLD);
        }
    }

    @Nested
    @DisplayName("voidConsumption")
    class VoidConsumption {

        @Test
        @DisplayName("異常系: 既に取消済みの消化は再取消不可")
        void 既に取消済み() {
            TicketBookEntity book = TicketBookEntity.builder().teamId(TEAM_ID).totalTickets(10).build();
            given(bookRepository.findByIdAndTeamIdForUpdate(BOOK_ID, TEAM_ID)).willReturn(Optional.of(book));

            TicketConsumptionEntity consumption = TicketConsumptionEntity.builder()
                    .bookId(BOOK_ID).isVoided(true).build();
            given(consumptionRepository.findByIdAndBookId(1L, BOOK_ID)).willReturn(Optional.of(consumption));

            assertThatThrownBy(() -> service.voidConsumption(TEAM_ID, BOOK_ID, 1L, STAFF_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TicketErrorCode.ALREADY_VOIDED);
        }
    }

    @Nested
    @DisplayName("extendExpiry")
    class ExtendExpiry {

        @Test
        @DisplayName("異常系: 無期限チケットの延長はエラー")
        void 無期限チケット延長不可() {
            TicketBookEntity book = TicketBookEntity.builder()
                    .teamId(TEAM_ID).totalTickets(10).expiresAt(null).build();
            setStatus(book, TicketBookStatus.ACTIVE);
            given(bookRepository.findByIdAndTeamId(BOOK_ID, TEAM_ID)).willReturn(Optional.of(book));

            ExtendRequest request = new ExtendRequest(LocalDateTime.now().plusMonths(1), null);

            assertThatThrownBy(() -> service.extendExpiry(TEAM_ID, BOOK_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TicketErrorCode.CANNOT_EXTEND_NO_EXPIRY);
        }

        @Test
        @DisplayName("異常系: 使い切りチケットの延長はエラー")
        void 使い切り延長不可() {
            TicketBookEntity book = TicketBookEntity.builder()
                    .teamId(TEAM_ID).totalTickets(10).expiresAt(LocalDateTime.now().plusDays(30)).build();
            setStatus(book, TicketBookStatus.EXHAUSTED);
            given(bookRepository.findByIdAndTeamId(BOOK_ID, TEAM_ID)).willReturn(Optional.of(book));

            ExtendRequest request = new ExtendRequest(LocalDateTime.now().plusMonths(2), null);

            assertThatThrownBy(() -> service.extendExpiry(TEAM_ID, BOOK_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TicketErrorCode.CANNOT_EXTEND_EXHAUSTED);
        }

        @Test
        @DisplayName("異常系: 延長先が現在の有効期限以前はエラー")
        void 延長先が以前() {
            LocalDateTime currentExpiry = LocalDateTime.now().plusDays(30);
            TicketBookEntity book = TicketBookEntity.builder()
                    .teamId(TEAM_ID).totalTickets(10).expiresAt(currentExpiry).build();
            setStatus(book, TicketBookStatus.ACTIVE);
            given(bookRepository.findByIdAndTeamId(BOOK_ID, TEAM_ID)).willReturn(Optional.of(book));

            ExtendRequest request = new ExtendRequest(currentExpiry.minusDays(1), null);

            assertThatThrownBy(() -> service.extendExpiry(TEAM_ID, BOOK_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TicketErrorCode.EXTEND_DATE_NOT_FUTURE);
        }
    }

    @Nested
    @DisplayName("createCheckout")
    class CreateCheckout {

        @Test
        @DisplayName("異常系: 販売停止中の商品はチェックアウト不可")
        void 販売停止中() {
            TicketProductEntity product = TicketProductEntity.builder()
                    .teamId(TEAM_ID).name("テスト").isActive(false).build();
            given(productRepository.findByIdAndTeamId(1L, TEAM_ID)).willReturn(Optional.of(product));

            assertThatThrownBy(() -> service.createCheckout(TEAM_ID, 1L, 100L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TicketErrorCode.PRODUCT_NOT_ACTIVE);
        }

        @Test
        @DisplayName("異常系: オンライン購入不可の商品はチェックアウト不可")
        void オンライン購入不可() {
            TicketProductEntity product = TicketProductEntity.builder()
                    .teamId(TEAM_ID).name("テスト").isActive(true).isOnlinePurchasable(false).build();
            given(productRepository.findByIdAndTeamId(1L, TEAM_ID)).willReturn(Optional.of(product));

            assertThatThrownBy(() -> service.createCheckout(TEAM_ID, 1L, 100L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TicketErrorCode.PRODUCT_NOT_ONLINE_PURCHASABLE);
        }
    }

    @Nested
    @DisplayName("refund")
    class Refund {

        @Test
        @DisplayName("異常系: 既に返金済みの場合エラー")
        void 既に返金済み() {
            TicketBookEntity book = TicketBookEntity.builder()
                    .teamId(TEAM_ID).totalTickets(10).paymentId(1L).build();
            setStatus(book, TicketBookStatus.CANCELLED);
            given(bookRepository.findByIdAndTeamId(BOOK_ID, TEAM_ID)).willReturn(Optional.of(book));

            RefundRequest request = new RefundRequest("FULL", null, null, null);

            assertThatThrownBy(() -> service.refund(TEAM_ID, BOOK_ID, STAFF_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TicketErrorCode.ALREADY_REFUNDED);
        }
    }

    @Nested
    @DisplayName("consumeByQr")
    class ConsumeByQr {

        @Test
        @DisplayName("異常系: QRペイロード形式不正")
        void QRペイロード不正() {
            given(ticketQrService.validateAndConsumeToken("invalid")).willThrow(new IllegalArgumentException());

            assertThatThrownBy(() -> service.consumeByQr(TEAM_ID, STAFF_ID, "invalid", null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TicketErrorCode.QR_PAYLOAD_INVALID);
        }

        @Test
        @DisplayName("異常系: QRトークン無効")
        void QRトークン無効() {
            given(ticketQrService.validateAndConsumeToken("tkt_1_otp_expired")).willThrow(new IllegalStateException());

            assertThatThrownBy(() -> service.consumeByQr(TEAM_ID, STAFF_ID, "tkt_1_otp_expired", null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TicketErrorCode.QR_TOKEN_INVALID);
        }
    }

    private void setStatus(TicketBookEntity book, TicketBookStatus status) {
        try {
            Field field = TicketBookEntity.class.getDeclaredField("status");
            field.setAccessible(true);
            field.set(book, status);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
