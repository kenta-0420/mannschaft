package com.mannschaft.app.publicview.repository;

import com.mannschaft.app.publicview.entity.PublicPostCommentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * F19.1 Phase 6-B: 公開投稿コメントリポジトリ。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.7 Phase 6-B</p>
 */
public interface PublicPostCommentRepository extends JpaRepository<PublicPostCommentEntity, UUID> {

    /**
     * 投稿 ID に対する有効なコメント一覧を作成日時昇順で取得する。
     *
     * <p>論理削除済み（{@code deleted_at IS NOT NULL}）のコメントは除外する。</p>
     *
     * @param postId   対象 BlogPost の ID
     * @param pageable ページネーション
     * @return 有効なコメントのページ（作成日時 ASC）
     */
    @Query("""
            SELECT c FROM PublicPostCommentEntity c
            WHERE c.postId = :postId
              AND c.deletedAt IS NULL
            ORDER BY c.createdAt ASC
            """)
    Page<PublicPostCommentEntity> findActiveByPostId(
            @Param("postId") Long postId, Pageable pageable);
}
