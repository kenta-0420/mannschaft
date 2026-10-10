package com.mannschaft.app.ranch.repository;

import com.mannschaft.app.ranch.entity.RanchOwnerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 本人user IDで検索範囲を固定する。 */
public interface RanchOwnerRepository extends JpaRepository<RanchOwnerEntity, UUID> {
    Optional<RanchOwnerEntity> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from RanchOwnerEntity o where o.userId = :userId")
    Optional<RanchOwnerEntity> lockByUserId(@Param("userId") Long userId);
}
