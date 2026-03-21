package com.mannschaft.app.circulation.repository;

import com.mannschaft.app.circulation.RecipientStatus;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 回覧受信者リポジトリ。
 */
public interface CirculationRecipientRepository extends JpaRepository<CirculationRecipientEntity, Long> {

    /**
     * 文書IDで受信者一覧を取得する（ソート順）。
     */
    List<CirculationRecipientEntity> findByDocumentIdOrderBySortOrderAsc(Long documentId);

    /**
     * 文書IDとユーザーIDで受信者を取得する。
     */
    Optional<CirculationRecipientEntity> findByDocumentIdAndUserId(Long documentId, Long userId);

    /**
     * 文書IDとユーザーIDで受信者の存在を確認する。
     */
    boolean existsByDocumentIdAndUserId(Long documentId, Long userId);

    /**
     * 文書IDで受信者数を取得する。
     */
    long countByDocumentId(Long documentId);

    /**
     * 文書IDとステータスで受信者数を取得する。
     */
    long countByDocumentIdAndStatus(Long documentId, RecipientStatus status);

    /**
     * 文書IDで受信者を全削除する。
     */
    void deleteAllByDocumentId(Long documentId);

    /**
     * 文書IDとステータスで受信者一覧を取得する。
     */
    List<CirculationRecipientEntity> findByDocumentIdAndStatusOrderBySortOrderAsc(
            Long documentId, RecipientStatus status);
}
