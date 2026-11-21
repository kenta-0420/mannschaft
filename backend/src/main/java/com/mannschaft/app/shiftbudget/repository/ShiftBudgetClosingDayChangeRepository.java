package com.mannschaft.app.shiftbudget.repository;

import com.mannschaft.app.shiftbudget.entity.ShiftBudgetClosingDayChangeEntity;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 締め日の変更履歴リポジトリ（追記専用。CMP-260909-2152）。UPDATE／DELETE の経路を持たない（{@link Repository} 継承で
 * 公開するのは INSERT と参照のみ）。
 */
@org.springframework.stereotype.Repository
public interface ShiftBudgetClosingDayChangeRepository extends Repository<ShiftBudgetClosingDayChangeEntity, UUID> {

    ShiftBudgetClosingDayChangeEntity save(ShiftBudgetClosingDayChangeEntity entity);

    /** 組織の履歴を revision 昇順で1回で取る（期間解決はこの1回の読取で行い N+1 を作らない）。 */
    List<ShiftBudgetClosingDayChangeEntity> findByOrganizationIdOrderByRevisionAsc(Long organizationId);
}
