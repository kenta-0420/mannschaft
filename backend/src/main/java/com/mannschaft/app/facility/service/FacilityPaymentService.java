package com.mannschaft.app.facility.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.facility.FacilityErrorCode;
import com.mannschaft.app.facility.FacilityMapper;
import com.mannschaft.app.facility.PaymentMethod;
import com.mannschaft.app.facility.dto.BookingPaymentResponse;
import com.mannschaft.app.facility.entity.FacilityBookingEntity;
import com.mannschaft.app.facility.entity.FacilityBookingPaymentEntity;
import com.mannschaft.app.facility.repository.FacilityBookingPaymentRepository;
import com.mannschaft.app.facility.repository.FacilityBookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 施設予約支払いサービス（placeholder）。
 * Stripe連携は将来実装。現時点ではDIRECT支払いのみ対応。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FacilityPaymentService {

    private final FacilityBookingPaymentRepository paymentRepository;
    private final FacilityBookingRepository bookingRepository;
    private final FacilityMapper facilityMapper;

    /**
     * 予約の支払い情報を取得する。
     */
    public BookingPaymentResponse getPayment(Long bookingId) {
        FacilityBookingPaymentEntity payment = paymentRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new BusinessException(FacilityErrorCode.PAYMENT_NOT_FOUND));
        return facilityMapper.toBookingPaymentResponse(payment);
    }

    /**
     * DIRECT支払いを確認する。
     */
    @Transactional
    public BookingPaymentResponse confirmDirectPayment(Long bookingId, Long adminUserId) {
        FacilityBookingPaymentEntity payment = paymentRepository.findByBookingId(bookingId)
                .orElseGet(() -> createDirectPayment(bookingId));

        payment.confirmDirectPayment();
        paymentRepository.save(payment);
        return facilityMapper.toBookingPaymentResponse(payment);
    }

    private FacilityBookingPaymentEntity createDirectPayment(Long bookingId) {
        FacilityBookingEntity booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BusinessException(FacilityErrorCode.BOOKING_NOT_FOUND));

        FacilityBookingPaymentEntity payment = FacilityBookingPaymentEntity.builder()
                .bookingId(bookingId)
                .payerUserId(booking.getBookedBy())
                .paymentMethod(PaymentMethod.DIRECT)
                .amount(booking.getTotalFee())
                .build();

        return paymentRepository.save(payment);
    }
}
