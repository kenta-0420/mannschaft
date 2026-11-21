package com.mannschaft.app.shiftbudget.repository;

import com.mannschaft.app.shiftbudget.entity.ShiftBudgetClosePeriodEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 組織の締め期間の確定行リポジトリ（CMP-260909-2152）。
 */
@Repository
public interface ShiftBudgetClosePeriodRepository extends JpaRepository<ShiftBudgetClosePeriodEntity, UUID> {

    Optional<ShiftBudgetClosePeriodEntity> findByOrganizationIdAndPeriodKey(Long organizationId, String periodKey);

    /** 期間キー（YYYY-MM は辞書順＝時系列）の範囲で確定済みの期間行を取る（1回の読取）。 */
    List<ShiftBudgetClosePeriodEntity> findByOrganizationIdAndPeriodKeyBetweenOrderByPeriodKeyAsc(
            Long organizationId, String fromKey, String toKey);

    /** 組織の確定済み期間の最大期間キー（無ければ null）。旧締めの取り込み行を含む。 */
    @Query("SELECT MAX(p.periodKey) FROM ShiftBudgetClosePeriodEntity p WHERE p.organizationId = :organizationId")
    String findMaxPeriodKey(@Param("organizationId") Long organizationId);
}
