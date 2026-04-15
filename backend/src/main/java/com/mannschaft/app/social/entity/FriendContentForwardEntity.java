package com.mannschaft.app.social.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * フレンドコンテンツ転送履歴エンティティ。
 * 管理者が「転送」を実行した履歴を冪等性担保（二重転送防止）と監査ログとして保持する。
 *
 * <p>
 * <b>冪等性</b>: 同じ {@code source_post_id} を同じ {@code forwarding_team_id} で
 * 同時に2回転送しようとすると、アクティブレコード（{@code is_revoked = FALSE}）が
 * 既に存在するため UNIQUE 制約違反 → 409 Conflict。
 * </p>
 *
 * <p>
 * <b>取消実装</b>: {@code is_revoked = TRUE} にフラグを立てる。同時に
 * {@code revoked_at = NOW()} / {@code revoked_by = user_id} を記録。
 * 取消後の再転送時は、旧レコードを保持したまま新レコードを {@code is_revoked = FALSE}
 * で INSERT できる。
 * </p>
 *
 * <p>
 * <b>連鎖削除</b>: 転送元 {@code timeline_posts} が物理削除された場合、
 * FK の {@code ON DELETE CASCADE} により本レコードが自動削除される。
 * </p>
 */
@Entity
@Table(
        name = "friend_content_forwards",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_fcf_active",
                        columnNames = {"source_post_id", "forwarding_team_id", "is_revoked"}
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class FriendContentForwardEntity extends BaseEntity {

    /** 転送元投稿 ID（timeline_posts.id） */
    @Column(name = "source_post_id", nullable = false)
    private Long sourcePostId;

    /** 転送元チーム ID（フレンドチーム側 = 投稿を生成した側） */
    @Column(name = "source_team_id", nullable = false)
    private Long sourceTeamId;

    /** 転送実行チーム ID（自チーム = 転送操作を行った側） */
    @Column(name = "forwarding_team_id", nullable = false)
    private Long forwardingTeamId;

    /** 転送で生成された自チーム内投稿 ID（timeline_posts.id） */
    @Column(name = "forwarded_post_id", nullable = false)
    private Long forwardedPostId;

    /**
     * 配信範囲。Phase 1 は {@code 'MEMBER'} 固定。
     * Phase 3 以降で {@code 'MEMBER_AND_SUPPORTER'} 解禁予定。
     */
    @Column(name = "target", nullable = false, length = 30)
    @Builder.Default
    private String target = "MEMBER";

    /** 転送時に管理者が付与したコメント（任意） */
    @Column(name = "comment", length = 500)
    private String comment;

    /** 転送取消フラグ。{@code TRUE} = 取消済み / {@code FALSE} = アクティブ */
    @Column(name = "is_revoked", nullable = false)
    @Builder.Default
    private Boolean isRevoked = false;

    /** 転送操作を行った ADMIN / DEPUTY_ADMIN のユーザー ID */
    @Column(name = "forwarded_by", nullable = false)
    private Long forwardedBy;

    /** 取消実行者のユーザー ID（取消前は NULL） */
    @Column(name = "revoked_by")
    private Long revokedBy;

    /** 転送実行日時 */
    @Column(name = "forwarded_at", nullable = false)
    private LocalDateTime forwardedAt;

    /** 取消日時（{@code is_revoked = TRUE} の場合のみ値を持つ） */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /**
     * {@code forwarded_at} が未設定の場合に現在時刻で補完する。
     */
    @PrePersist
    protected void onCreateForward() {
        if (this.forwardedAt == null) {
            this.forwardedAt = LocalDateTime.now();
        }
    }

    /**
     * 転送を取消する。{@code is_revoked} を {@code TRUE} に、
     * {@code revokedAt} を現在時刻に、{@code revokedBy} を引数に設定する。
     *
     * @param revokedBy 取消実行者のユーザー ID
     */
    public void revoke(Long revokedBy) {
        this.isRevoked = true;
        this.revokedAt = LocalDateTime.now();
        this.revokedBy = revokedBy;
    }
}
