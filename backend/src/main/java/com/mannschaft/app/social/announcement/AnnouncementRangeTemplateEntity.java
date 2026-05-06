package com.mannschaft.app.social.announcement;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 告知ウィザード範囲テンプレートエンティティ（F02.8）。
 *
 * <p>
 * ダッシュボード告知ウィザードの対象範囲設定（targetRole / targetTeamIds / preferredChannel）を
 * 保存・再利用するテンプレートテーブル {@code announcement_range_templates} のエンティティ。
 * </p>
 *
 * <p>
 * <b>is_default 制約</b>:
 * スコープごとにデフォルトテンプレートは最大1件。
 * Service 層の {@code @Transactional} で排他制御する。
 * </p>
 */
@Entity
@Table(name = "announcement_range_templates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AnnouncementRangeTemplateEntity extends BaseEntity {

    /** 適用スコープ種別（TEAM / ORGANIZATION） */
    @Column(nullable = false, length = 20)
    private String scopeType;

    /** 適用スコープ ID（teams.id または organizations.id） */
    @Column(nullable = false)
    private Long scopeId;

    /** テンプレート名（1〜100文字） */
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * 告知対象ロール。
     * 値: MEMBERS_ONLY / SUPPORTERS_AND_ABOVE / PUBLIC
     */
    @Column(nullable = false, length = 30)
    @Builder.Default
    private String targetRole = "MEMBERS_ONLY";

    /**
     * 組織告知でのチーム絞り込み対象（JSON 配列で team.id を保持）。
     * NULL = 全チーム対象。値例: "[1,3,5]"
     */
    @Column(columnDefinition = "JSON")
    private String targetTeamIds;

    /**
     * 優先チャネル。
     * 値: BULLETIN_THREAD / TIMELINE_POST / BLOG_POST / TODO / SCHEDULE / SURVEY
     * NULL = 優先チャネル未設定（ウィザードでステップ2を都度選ぶ）
     */
    @Column(length = 30)
    private String preferredChannel;

    /**
     * デフォルトテンプレートフラグ。
     * スコープごとに最大1件（Service 層で排他制御）。
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    /** 作成者ユーザー ID（退会時は NULL に設定）。 */
    @Column
    private Long createdBy;

    // --- ドメインメソッド ---

    /**
     * テンプレート内容を更新する。
     */
    public void update(String name, String targetRole, String targetTeamIds,
                       String preferredChannel) {
        this.name = name;
        this.targetRole = targetRole;
        this.targetTeamIds = targetTeamIds;
        this.preferredChannel = preferredChannel;
    }

    /** デフォルトフラグを設定する。 */
    public void setAsDefault() {
        this.isDefault = true;
    }

    /** デフォルトフラグを解除する。 */
    public void clearDefault() {
        this.isDefault = false;
    }
}
