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
 * フレンドチーム関係エンティティ。相互フォロー成立時に生成されるキャッシュテーブル。
 *
 * <p>
 * {@code follows} テーブルの {@code (TEAM, A) ↔ (TEAM, B)} 双方向エントリから自動的に導出される。
 * 検索・一覧表示の高速化のために正規化せずに保持する。
 * </p>
 *
 * <p>
 * <b>CHECK 制約</b>: DB 側で {@code CHECK (team_a_id &lt; team_b_id)} により
 * 同一ペアの重複を防止しつつ順序を正規化する（MySQL 8.0 以降必須）。
 * アプリ層でも Service のバリデーションで事前に正規化するが、二重安全ネットとして
 * DB レベルでも強制する。
 * </p>
 *
 * <p>
 * <b>論理削除</b>: 行わない。フレンド解除時は物理削除。
 * </p>
 */
@Entity
@Table(
        name = "team_friends",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_tf_pair", columnNames = {"team_a_id", "team_b_id"})
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TeamFriendEntity extends BaseEntity {

    /** 関係の片側（常に team_a_id &lt; team_b_id となるよう正規化） */
    @Column(name = "team_a_id", nullable = false)
    private Long teamAId;

    /** 関係のもう片側 */
    @Column(name = "team_b_id", nullable = false)
    private Long teamBId;

    /** 相互フォロー成立日時（フレンドシップ成立日） */
    @Column(name = "established_at", nullable = false)
    private LocalDateTime establishedAt;

    /** A→B のフォローレコード ID（監査・整合性チェック用） */
    @Column(name = "a_follow_id", nullable = false)
    private Long aFollowId;

    /** B→A のフォローレコード ID（監査・整合性チェック用） */
    @Column(name = "b_follow_id", nullable = false)
    private Long bFollowId;

    /**
     * フレンド関係を公開するか。
     * {@code TRUE} の場合のみパブリック API から閲覧可能。
     * 両チームの ADMIN 承認により切り替え。デフォルトは {@code FALSE}（プライバシー安全側）。
     */
    @Column(name = "is_public", nullable = false)
    @Builder.Default
    private Boolean isPublic = false;

    /**
     * {@code established_at} が未設定の場合に現在時刻で補完する。
     */
    @PrePersist
    protected void onCreateTeamFriend() {
        if (this.establishedAt == null) {
            this.establishedAt = LocalDateTime.now();
        }
    }

    /**
     * フレンド関係の公開設定を切り替える。
     *
     * @param isPublic 公開フラグ
     */
    public void changePublicity(boolean isPublic) {
        this.isPublic = isPublic;
    }
}
