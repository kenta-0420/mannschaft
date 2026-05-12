package com.mannschaft.app.repairplan.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.repairplan.entity.BoardHandoverPack;

import java.util.List;
import java.util.UUID;

/**
 * 申し送りパックリポジトリ。
 */
public interface BoardHandoverPackRepository extends AbstractTenantAwareRepository<BoardHandoverPack, UUID> {

    /** スコープ単位の申し送りパック取得（年度降順）。 */
    List<BoardHandoverPack> findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByTermYearDesc(
            String scopeType, Long scopeId);

    /** スコープ × 年度単位のパック取得（GDPR 再生成での複数バージョン許容）。 */
    List<BoardHandoverPack> findByScopeTypeAndScopeIdAndTermYearAndDeletedAtIsNullOrderByCreatedAtDesc(
            String scopeType, Long scopeId, Integer termYear);

    /** ステータス単位の取得（GENERATING の追跡など）。 */
    List<BoardHandoverPack> findByStatusAndDeletedAtIsNull(String status);
}
