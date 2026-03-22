package com.mannschaft.app.facility.repository;

import com.mannschaft.app.facility.entity.FacilityBookingPaymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 予約支払いリポジトリ。
 */
public interface FacilityBookingPaymentRepository extends JpaRepository<FacilityBookingPaymentEntity, Long> {

    Optional<FacilityBookingPaymentEntity> findByBookingId(Long bookingId);
}
