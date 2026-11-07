package com.mannschaft.app.ranch.repository;

import com.mannschaft.app.ranch.entity.RanchCommandEntity;
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
public interface RanchCommandRepository extends JpaRepository<RanchCommandEntity, UUID> {
    Optional<RanchCommandEntity> findByUserIdAndIdempotencyKey(Long userId, UUID idempotencyKey);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM RanchCommandEntity c WHERE c.userId = :userId "
            + "AND c.idempotencyKey = :key")
    Optional<RanchCommandEntity> lockByUserIdAndIdempotencyKey(
            @Param("userId") Long userId, @Param("key") UUID key);
    Optional<RanchCommandEntity> findByUserIdAndId(Long userId, UUID id);
    long countByUserId(Long userId);
}
