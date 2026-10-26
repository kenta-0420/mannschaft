package com.mannschaft.app.circulation.repository;

import com.mannschaft.app.circulation.RecipientStatus;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 回覧受信者リポジトリ。
 */
public interface CirculationRecipientRepository extends JpaRepository<CirculationRecipientEntity, Long>,
        JpaSpecificationExecutor<CirculationRecipientEntity> {

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

    /**
     * F22.1 第二波: 指定スコープで、当該ユーザーが「未確認（PENDING）」の回覧文書を
     * 直近作成順に取得する。
     *
     * <p>受信者（recipient）が PENDING のまま、かつ文書が ACTIVE（公開中・未削除）であるものを
     * 対象とする。N+1 回避のため受信者と文書を 1 SQL で JOIN して文書エンティティを直接返す。
     * 文書の {@code @SQLRestriction("deleted_at IS NULL")} により論理削除済は自動除外される。</p>
     *
     * @param scopeType スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   スコープ ID
     * @param userId    閲覧ユーザー ID
     * @return 未確認回覧文書（作成日時の降順）
     */
    @Query("""
            SELECT d FROM CirculationRecipientEntity r
            JOIN CirculationDocumentEntity d ON d.id = r.documentId
            WHERE r.userId = :userId
              AND r.status = com.mannschaft.app.circulation.RecipientStatus.PENDING
              AND d.scopeType = :scopeType
              AND d.scopeId = :scopeId
              AND d.status = com.mannschaft.app.circulation.CirculationStatus.ACTIVE
            ORDER BY d.createdAt DESC
            """)
    List<CirculationDocumentEntity> findUnconfirmedDocumentsForUserInScope(
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId,
            @Param("userId") Long userId);
}
