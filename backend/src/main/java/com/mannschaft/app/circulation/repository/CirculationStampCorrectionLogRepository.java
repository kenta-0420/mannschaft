package com.mannschaft.app.circulation.repository;

import com.mannschaft.app.circulation.entity.CirculationStampCorrectionLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * 押印訂正履歴リポジトリ。
 */
public interface CirculationStampCorrectionLogRepository
        extends JpaRepository<CirculationStampCorrectionLogEntity, UUID> {

    /** 受信者IDで訂正履歴を取得（時系列）。 */
    List<CirculationStampCorrectionLogEntity> findByRecipientIdOrderByCreatedAtAsc(Long recipientId);

    /** 文書IDで訂正履歴件数を取得。 */
    long countByDocumentId(Long documentId);
}
