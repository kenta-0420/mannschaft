package com.mannschaft.app.disclosure.repository;

import com.mannschaft.app.disclosure.entity.DisclosureAutoDeleteBatchLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 重要事項説明書 自動削除バッチログのリポジトリ（F09.14 Phase 3-E）。
 */
public interface DisclosureAutoDeleteBatchLogRepository
        extends JpaRepository<DisclosureAutoDeleteBatchLogEntity, Long> {
}
