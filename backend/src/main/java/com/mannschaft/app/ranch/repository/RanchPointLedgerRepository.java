package com.mannschaft.app.ranch.repository;

import com.mannschaft.app.ranch.entity.RanchPointLedgerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 本人の不変台帳を取得する。 */
public interface RanchPointLedgerRepository extends JpaRepository<RanchPointLedgerEntity, UUID> {
    List<RanchPointLedgerEntity> findByUserIdOrderByOccurredAtDescIdDesc(Long userId);

    @Query(value = "SELECT * FROM ranch_point_ledger WHERE user_id = :userId "
            + "AND (:cursorAt IS NULL OR occurred_at < :cursorAt "
            + "OR (occurred_at = :cursorAt AND id < :cursorId)) "
            + "ORDER BY occurred_at DESC, id DESC LIMIT :limitPlusOne", nativeQuery = true)
    List<RanchPointLedgerEntity> pageForUser(@Param("userId") Long userId,
                                              @Param("cursorAt") Instant cursorAt,
                                              @Param("cursorId") UUID cursorId,
                                              @Param("limitPlusOne") int limitPlusOne);
}
