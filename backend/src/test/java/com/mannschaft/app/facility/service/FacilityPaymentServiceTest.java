package com.mannschaft.app.facility.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.facility.FacilityMapper;
import com.mannschaft.app.facility.PaymentMethod;
import com.mannschaft.app.facility.PaymentStatus;
import com.mannschaft.app.facility.dto.BookingPaymentResponse;
import com.mannschaft.app.facility.entity.FacilityBookingEntity;
import com.mannschaft.app.facility.entity.FacilityBookingPaymentEntity;
import com.mannschaft.app.facility.repository.FacilityBookingPaymentRepository;
import com.mannschaft.app.facility.repository.FacilityBookingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link FacilityPaymentService} の単体テスト。
 * 支払い情報取得・DIRECT支払い確認を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FacilityPaymentService 単体テスト")
class FacilityPaymentServiceTest {

    @Mock
    private FacilityBookingPaymentRepository paymentRepository;

    @Mock
    private FacilityBookingRepository bookingRepository;

    @Mock
    private FacilityMapper facilityMapper;

    @InjectMocks
    private FacilityPaymentService paymentService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long BOOKING_ID = 10L;
    private static final Long ADMIN_USER_ID = 200L;
    private static final Long USER_ID = 100L;

    private FacilityBookingPaymentEntity createPaymentEntity() {
        return FacilityBookingPaymentEntity.builder()
                .bookingId(BOOKING_ID)
                .payerUserId(USER_ID)
                .paymentMethod(PaymentMethod.DIRECT)
                .amount(BigDecimal.valueOf(3000))
                .status(PaymentStatus.PENDING)
                .build();
    }

    private FacilityBookingEntity createBookingEntity() {
        return FacilityBookingEntity.builder()
                .facilityId(5L)
                .bookedBy(USER_ID)
                .bookingDate(LocalDate.now().plusDays(3))
                .timeFrom(LocalTime.of(10, 0))
                .timeTo(LocalTime.of(12, 0))
                .slotCount(4)
                .totalFee(BigDecimal.valueOf(3000))
                .build();
    }

    // ========================================
    // getPayment
    // ========================================

    @Nested
    @DisplayName("getPayment")
    class GetPayment {

        @Test
        @DisplayName("正常系: 支払い情報が返る")
        void 支払い情報取得_正常_情報が返る() {
            // Given
            FacilityBookingPaymentEntity payment = createPaymentEntity();
            given(paymentRepository.findByBookingId(BOOKING_ID)).willReturn(Optional.of(payment));
            given(facilityMapper.toBookingPaymentResponse(payment)).willReturn(mock(BookingPaymentResponse.class));

            // When
            BookingPaymentResponse result = paymentService.getPayment(BOOKING_ID);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: 支払い情報が存在しないでFACILITY_017例外")
        void 支払い情報取得_存在しない_FACILITY017例外() {
            // Given
            given(paymentRepository.findByBookingId(BOOKING_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> paymentService.getPayment(BOOKING_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_017"));
        }
    }

    // ========================================
    // confirmDirectPayment
    // ========================================

    @Nested
    @DisplayName("confirmDirectPayment")
    class ConfirmDirectPayment {

        @Test
        @DisplayName("正常系: 既存支払いが確認される")
        void DIRECT支払い確認_既存支払い_SUCCEEDEDになる() {
            // Given
            FacilityBookingPaymentEntity payment = createPaymentEntity();
            given(paymentRepository.findByBookingId(BOOKING_ID)).willReturn(Optional.of(payment));
            given(paymentRepository.save(payment)).willReturn(payment);
            given(facilityMapper.toBookingPaymentResponse(payment)).willReturn(mock(BookingPaymentResponse.class));

            // When
            BookingPaymentResponse result = paymentService.confirmDirectPayment(BOOKING_ID, ADMIN_USER_ID);

            // Then
            assertThat(result).isNotNull();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(payment.getPaidAt()).isNotNull();
            verify(paymentRepository).save(payment);
        }

        @Test
        @DisplayName("正常系: 支払いレコードがない場合は新規作成して確認する")
        void DIRECT支払い確認_レコードなし_新規作成して確認される() {
            // Given
            FacilityBookingEntity booking = createBookingEntity();
            given(paymentRepository.findByBookingId(BOOKING_ID)).willReturn(Optional.empty());
            given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.of(booking));
            given(paymentRepository.save(any(FacilityBookingPaymentEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(facilityMapper.toBookingPaymentResponse(any(FacilityBookingPaymentEntity.class)))
                    .willReturn(mock(BookingPaymentResponse.class));

            // When
            BookingPaymentResponse result = paymentService.confirmDirectPayment(BOOKING_ID, ADMIN_USER_ID);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: 支払いレコードなし+予約なしでFACILITY_006例外")
        void DIRECT支払い確認_予約なし_FACILITY006例外() {
            // Given
            given(paymentRepository.findByBookingId(BOOKING_ID)).willReturn(Optional.empty());
            given(bookingRepository.findById(BOOKING_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> paymentService.confirmDirectPayment(BOOKING_ID, ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_006"));
        }
    }
}
