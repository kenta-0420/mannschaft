package com.mannschaft.app.repairplan.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.repairplan.entity.RepairQuoteCard;

import java.util.List;
import java.util.UUID;

/**
 * 業者見積カードリポジトリ。
 */
public interface RepairQuoteCardRepository extends AbstractTenantAwareRepository<RepairQuoteCard, UUID> {

    /** カンバン単位のカード取得（表示順）。 */
    List<RepairQuoteCard> findByKanbanIdAndDeletedAtIsNullOrderByDisplayOrderAsc(UUID kanbanId);

    /** カンバン × ステージ単位のカード取得。 */
    List<RepairQuoteCard> findByKanbanIdAndStageAndDeletedAtIsNullOrderByDisplayOrderAsc(
            UUID kanbanId, String stage);

    /** 業者単位の履歴取得（全カンバン横断）。 */
    List<RepairQuoteCard> findByVendorIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long vendorId);
}
