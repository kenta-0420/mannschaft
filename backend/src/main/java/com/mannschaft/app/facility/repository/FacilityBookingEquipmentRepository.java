package com.mannschaft.app.facility.repository;

import com.mannschaft.app.facility.entity.FacilityBookingEquipmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 予約備品紐付けリポジトリ。
 */
public interface FacilityBookingEquipmentRepository extends JpaRepository<FacilityBookingEquipmentEntity, Long> {

    List<FacilityBookingEquipmentEntity> findByBookingId(Long bookingId);

    void deleteByBookingId(Long bookingId);
}
