package com.mannschaft.app.publicview.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * F19.1 Phase 6-B: 公開投稿コメントエンティティ。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.7 Phase 6-B</p>
 *
 * <p>ログイン済みユーザーが {@code public_visible=true} の BlogPost に投稿できるコメントを表す。</p>
 *
 * <p><strong>論理削除</strong>: {@code deleted_at} による soft delete を使用する。
 * 投稿者 / ADMIN のみが削除できる。</p>
 */
@Entity
@Table(name = "public_post_comments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PublicPostCommentEntity extends UuidV7Entity {

    /**
     * コメント対象の BlogPost の ID。
     * TODO: publicview → cms のクロスドメイン参照。将来はイベント駆動化候補。
     */
    @Column(name = "post_id", nullable = false)
    private Long postId;

    /**
     * 投稿者ユーザー ID（users.id）。
     * TODO: publicview → user のクロスドメイン参照。将来はイベント駆動化候補。
     */
    @Column(name = "author_id", nullable = false)
    private Long authorId;

    /** コメント本文（最大 1000 文字）。 */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * 投稿者本名スナップショット。
     * 投稿者が属するチーム/組織の supporter_name_disclosure = REAL_NAME の場合のみ格納する。
     * DISPLAY_NAME モード時は NULL。
     */
    @Column(name = "author_real_name_snapshot", length = 100)
    private String authorRealNameSnapshot;

    /** コメント作成日時。 */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** 論理削除日時。NULL = 有効、非 NULL = 削除済み。 */
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    /**
     * コメントを生成するファクトリーメソッド。
     *
     * @param postId                  対象 BlogPost の ID
     * @param authorId                投稿者ユーザー ID
     * @param content                 コメント本文
     * @param authorRealNameSnapshot  本名スナップショット（DISPLAY_NAME モード時は null）
     * @return 新規コメントエンティティ
     */
    public static PublicPostCommentEntity create(
            Long postId,
            Long authorId,
            String content,
            String authorRealNameSnapshot) {
        PublicPostCommentEntity entity = new PublicPostCommentEntity();
        entity.postId = postId;
        entity.authorId = authorId;
        entity.content = content;
        entity.authorRealNameSnapshot = authorRealNameSnapshot;
        entity.createdAt = OffsetDateTime.now();
        return entity;
    }

    /**
     * コメントを論理削除する。
     */
    public void softDelete() {
        this.deletedAt = OffsetDateTime.now();
    }

    /**
     * コメントが削除済みかどうかを返す。
     *
     * @return 削除済みの場合 true
     */
    public boolean isDeleted() {
        return deletedAt != null;
    }
}
