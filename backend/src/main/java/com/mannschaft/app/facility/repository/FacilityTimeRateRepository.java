package com.mannschaft.app.facility.repository;

import com.mannschaft.app.facility.DayType;
import com.mannschaft.app.facility.entity.FacilityTimeRateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 施設時間帯別料金リポジトリ。
 */
public interface FacilityTimeRateRepository extends JpaRepository<FacilityTimeRateEntity, Long> {

    List<FacilityTimeRateEntity> findByFacilityIdOrderByDayTypeAscTimeFromAsc(Long facilityId);

    List<FacilityTimeRateEntity> findByFacilityIdAndDayType(Long facilityId, DayType dayType);

    void deleteByFacilityId(Long facilityId);
}
