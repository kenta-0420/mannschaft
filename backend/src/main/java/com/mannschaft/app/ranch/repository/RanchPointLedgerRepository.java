package com.mannschaft.app.ranch.repository;

import com.mannschaft.app.ranch.entity.RanchPointLedgerEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 本人の不変台帳を取得する。 */
public interface RanchPointLedgerRepository extends JpaRepository<RanchPointLedgerEntity, UUID> {
    List<RanchPointLedgerEntity> findByUserIdOrderByOccurredAtDescIdDesc(Long userId);

    @Query("SELECT entry FROM RanchPointLedgerEntity entry WHERE entry.userId = :userId "
            + "AND (:cursorAt IS NULL OR entry.occurredAt < :cursorAt "
            + "OR (entry.occurredAt = :cursorAt AND entry.id < :cursorId)) "
            + "ORDER BY entry.occurredAt DESC, entry.id DESC")
    List<RanchPointLedgerEntity> pageForUser(@Param("userId") Long userId,
                                              @Param("cursorAt") Instant cursorAt,
                                              @Param("cursorId") UUID cursorId,
                                              Pageable page);
}
