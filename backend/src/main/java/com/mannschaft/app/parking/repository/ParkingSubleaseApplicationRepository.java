package com.mannschaft.app.parking.repository;

import com.mannschaft.app.parking.entity.ParkingSubleaseApplicationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * サブリース申請リポジトリ。
 */
public interface ParkingSubleaseApplicationRepository extends JpaRepository<ParkingSubleaseApplicationEntity, Long> {

    List<ParkingSubleaseApplicationEntity> findBySubleaseId(Long subleaseId);
}
