package com.mannschaft.app.parking.repository;

import com.mannschaft.app.parking.entity.ParkingSubleasePaymentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * サブリース決済リポジトリ。
 */
public interface ParkingSubleasePaymentRepository extends JpaRepository<ParkingSubleasePaymentEntity, Long> {

    Page<ParkingSubleasePaymentEntity> findBySubleaseId(Long subleaseId, Pageable pageable);
}
