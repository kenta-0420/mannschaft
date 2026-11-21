package com.mannschaft.app.shiftbudget.repository;

import com.mannschaft.app.shiftbudget.entity.ShiftBudgetClosingSettingEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * 組織の締め日設定の先頭行リポジトリ（CMP-260909-2152）。締め・締め日変更の直列化ロック対象。
 */
@Repository
public interface ShiftBudgetClosingSettingRepository extends JpaRepository<ShiftBudgetClosingSettingEntity, UUID> {

    /** 参照（ロックなし）。GET は行を作らないため空を返しうる。 */
    Optional<ShiftBudgetClosingSettingEntity> findByOrganizationId(Long organizationId);

    /** 先頭行を {@code FOR UPDATE} で取る（INSERT IGNORE の後に呼ぶ）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ShiftBudgetClosingSettingEntity s WHERE s.organizationId = :organizationId")
    Optional<ShiftBudgetClosingSettingEntity> findByOrganizationIdForUpdate(@Param("organizationId") Long organizationId);

    /** 先頭行が無ければ作る（組織の UNIQUE により並行しても1行）。 */
    @Transactional
    @Modifying
    @Query(value = "INSERT IGNORE INTO shift_budget_closing_settings (id, organization_id, revision, created_at, updated_at) "
            + "VALUES (:id, :organizationId, 0, NOW(6), NOW(6))", nativeQuery = true)
    int insertIgnore(@Param("id") byte[] id, @Param("organizationId") Long organizationId);
}
