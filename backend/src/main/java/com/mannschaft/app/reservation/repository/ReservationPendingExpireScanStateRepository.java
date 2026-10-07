package com.mannschaft.app.reservation.repository;

import com.mannschaft.app.reservation.entity.ReservationPendingExpireScanStateEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

/** 全runner共通の1行をunit TX終了まで排他する。 */
public interface ReservationPendingExpireScanStateRepository
        extends JpaRepository<ReservationPendingExpireScanStateEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT state FROM ReservationPendingExpireScanStateEntity state WHERE state.singletonKey = 1")
    Optional<ReservationPendingExpireScanStateEntity> lockSingleton();
}
