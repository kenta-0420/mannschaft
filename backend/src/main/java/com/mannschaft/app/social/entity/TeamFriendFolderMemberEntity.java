package com.mannschaft.app.social.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
 * フレンドフォルダメンバーエンティティ。
 * フレンドフォルダとフレンドチームの多対多中間テーブル。
 *
 * <p>
 * 1つのフレンドチームは複数フォルダに所属可能（例: 「系列校」かつ「練習試合候補」）。
 * フレンド解除時（{@code team_friends} が削除される時）は CASCADE により自動で関連レコードも削除される。
 * </p>
 *
 * <p>
 * このエンティティは作成日時のみを保持し、更新は行わない（純粋な追加・削除モデル）。
 * そのため {@link com.mannschaft.app.common.BaseEntity} は継承せず、
 * {@code addedAt} のみを持つ独自定義とする。
 * </p>
 */
@Entity
@Table(
        name = "team_friend_folder_members",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_tffm_folder_friend",
                        columnNames = {"folder_id", "team_friend_id"}
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TeamFriendFolderMemberEntity {

    /** PK */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属先フォルダの ID */
    @Column(name = "folder_id", nullable = false)
    private Long folderId;

    /** 対象のフレンドチーム関係 ID（team_friends.id） */
    @Column(name = "team_friend_id", nullable = false)
    private Long teamFriendId;

    /** フォルダへの追加日時 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime addedAt;

    /**
     * 追加日時を現在時刻で自動設定する。
     */
    @PrePersist
    protected void onCreate() {
        if (this.addedAt == null) {
            this.addedAt = LocalDateTime.now();
        }
    }
}
