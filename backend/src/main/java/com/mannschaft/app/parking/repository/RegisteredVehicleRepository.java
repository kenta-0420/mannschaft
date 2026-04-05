package com.mannschaft.app.parking.repository;

import com.mannschaft.app.parking.entity.RegisteredVehicleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 登録車両リポジトリ。
 */
public interface RegisteredVehicleRepository extends JpaRepository<RegisteredVehicleEntity, Long> {

    List<RegisteredVehicleEntity> findByUserId(Long userId);

    Optional<RegisteredVehicleEntity> findByIdAndUserId(Long id, Long userId);

    Optional<RegisteredVehicleEntity> findByPlateNumberHash(String plateNumberHash);
}
