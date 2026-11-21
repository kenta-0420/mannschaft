package com.mannschaft.app.budget.repository;

import com.mannschaft.app.budget.entity.BudgetShiftClosePostingEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * 締めの投稿判定表リポジトリ（追記専用。CMP-260909-2152 確定設計 r12 §1.2）。
 *
 * <p>UPDATE／DELETE の経路を持たない（{@link Repository} 継承で INSERT と参照のみを公開し、{@code @Modifying} も置かない）。</p>
 */
@org.springframework.stereotype.Repository
public interface BudgetShiftClosePostingRepository extends Repository<BudgetShiftClosePostingEntity, UUID> {

    BudgetShiftClosePostingEntity saveAndFlush(BudgetShiftClosePostingEntity entity);

    /** 判定行を {@code FOR UPDATE} で取る（年度行の後。あれば保存済み判定を返す）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM BudgetShiftClosePostingEntity p WHERE p.closeEventId = :closeEventId")
    Optional<BudgetShiftClosePostingEntity> findByCloseEventIdForUpdate(@Param("closeEventId") UUID closeEventId);
}
