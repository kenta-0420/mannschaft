package com.mannschaft.app.timeline.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.PostedAsType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * タイムライン投稿エンティティ。スコープ別（PUBLIC/ORGANIZATION/TEAM/PERSONAL/VILLAGE）の投稿を管理する。
 *
 * <p>F17.1 Phase 1: 村スコープ対応のため {@code scope_village_id} を追加。
 * {@code scopeType=VILLAGE} の場合に村の UUIDv7 を保持する。
 * 投稿主体（{@code postedAsType} / {@code postedAsId}）は既存カラムを流用する。</p>
 */
@Entity
@Table(name = "timeline_posts")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class TimelinePostEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PostScopeType scopeType;

    @Column(nullable = false)
    @Builder.Default
    private Long scopeId = 0L;

    /**
     * 村スコープ ID（F17.1 Phase 1）。
     * {@code scopeType=VILLAGE} の場合に村の UUIDv7 を保持する。FK は張らない（原則1）。
     */
    @Column(name = "scope_village_id", columnDefinition = "BINARY(16)")
    private UUID scopeVillageId;

    @Column(nullable = false)
    private Long userId;

    private Long socialProfileId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PostedAsType postedAsType = PostedAsType.USER;

    private Long postedAsId;

    private Long parentId;

    @Column(columnDefinition = "TEXT")
    private String content;

    private Long repostOfId;

    @Column(nullable = false)
    @Builder.Default
    private Integer repostCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PostStatus status = PostStatus.PUBLISHED;

    private LocalDateTime scheduledAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isPinned = false;

    @Column(nullable = false)
    @Builder.Default
    private Integer reactionCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer replyCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Short attachmentCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Short editCount = 0;

    /**
     * F01.5 フレンドチームへの共有可否フラグ。
     * 投稿作成時に ADMIN / DEPUTY_ADMIN が指定し、管理者フィードへの配信許可を表す。
     * デフォルトは {@code false}。V9.076 で追加。
     */
    @Column(name = "share_with_friends", nullable = false)
    @Builder.Default
    private Boolean shareWithFriends = false;

    /**
     * F01.5 転送元投稿 ID（転送で生成された投稿のみ値を持つ）。
     * {@link PostScopeType#FRIEND_FORWARD} 投稿の出典表示・逆引きに使用する。
     * V9.076 で追加（FK 制約 fk_tp_forward_source: ON DELETE SET NULL）。
     */
    @Column(name = "forward_source_post_id")
    private Long forwardSourcePostId;

    /**
     * F01.5 転送配信範囲メタデータ（{@code MEMBER} / {@code MEMBER_AND_SUPPORTER}）。
     * Phase 1 は {@code MEMBER} のみ。V9.076 で追加。
     */
    @Column(name = "forward_target_range", length = 30)
    private String forwardTargetRange;

    /**
     * F19.1 Phase 2: 投稿時の本名スナップショット。
     * 投稿者が属するチーム/組織の supporter_name_disclosure = REAL_NAME の場合のみ格納する。
     * DISPLAY_NAME モード時は NULL。
     */
    @Column(name = "author_real_name_snapshot", length = 100)
    private String authorRealNameSnapshot;

    /**
     * F19.1 Phase 2: 投稿の公開表示フラグ。
     * false の場合、公開ページ・sitemap・OGP から除外する（ログイン後の通常ビューには変化なし）。
     */
    @Column(name = "public_visible", nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT TRUE")
    @Builder.Default
    private boolean publicVisible = true;

    private LocalDateTime deletedAt;

    /**
     * 投稿を論理削除する。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
        this.status = PostStatus.DELETED;
    }

    /**
     * 投稿を非表示にする（モデレーション対応）。
     */
    public void hide() {
        this.status = PostStatus.HIDDEN;
    }

    /**
     * 投稿内容を更新し、編集回数をインクリメントする。
     *
     * @param newContent 新しい投稿内容
     */
    public void updateContent(String newContent) {
        this.content = newContent;
        this.editCount = (short) (this.editCount + 1);
    }

    /**
     * リアクション数をインクリメントする。
     */
    public void incrementReactionCount() {
        this.reactionCount++;
    }

    /**
     * リアクション数をデクリメントする。
     */
    public void decrementReactionCount() {
        if (this.reactionCount > 0) {
            this.reactionCount--;
        }
    }

    /**
     * リプライ数をインクリメントする。
     */
    public void incrementReplyCount() {
        this.replyCount++;
    }

    /**
     * リプライ数をデクリメントする（返信削除時。作成時の {@link #incrementReplyCount()} と対称）。
     * 負値ガードを備え、0 未満にはならない（0 でクランプ）。
     */
    public void decrementReplyCount() {
        if (this.replyCount > 0) {
            this.replyCount--;
        }
    }

    /**
     * リポスト数をインクリメントする。
     */
    public void incrementRepostCount() {
        this.repostCount++;
    }

    /**
     * ピン留め状態を切り替える。
     *
     * @param pinned ピン留めするかどうか
     */
    public void setPinned(boolean pinned) {
        this.isPinned = pinned;
    }

    /**
     * 返信投稿かどうかを判定する。
     *
     * @return parentId が設定されている場合 true
     */
    public boolean isReply() {
        return this.parentId != null;
    }

    /**
     * リポスト投稿かどうかを判定する。
     *
     * @return repostOfId が設定されている場合 true
     */
    public boolean isRepost() {
        return this.repostOfId != null;
    }
}
