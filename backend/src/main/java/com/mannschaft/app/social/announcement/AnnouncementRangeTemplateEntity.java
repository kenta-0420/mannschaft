package com.mannschaft.app.social.announcement;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 告知ウィザード 対象範囲テンプレートエンティティ（F02.8）。
 *
 * <p>告知の対象範囲設定（target_role + target_team_ids + preferred_channel）を
 * 保存・再利用するためのテンプレートを管理する。</p>
 */
@Entity
@Table(name = "announcement_range_templates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AnnouncementRangeTemplateEntity extends BaseEntity {

    /**
     * スコープ種別（TEAM / ORGANIZATION）。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnnouncementScopeType scopeType;

    /**
     * スコープ ID（teams.id または organizations.id）。
     */
    @Column(nullable = false)
    private Long scopeId;

    /**
     * テンプレート名（1〜100 文字）。
     */
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * 告知対象ロール（MEMBERS_AND_ABOVE / SUPPORTERS_AND_ABOVE / PUBLIC）。
     */
    @Column(nullable = false, length = 30)
    @Builder.Default
    private String targetRole = "MEMBERS_AND_ABOVE";

    /**
     * 組織告知でのチーム絞り込み（JSON 配列）。NULL = 全チーム対象。
     */
    @Column(columnDefinition = "JSON")
    private String targetTeamIds;

    /**
     * 優先チャネル（BULLETIN_THREAD / TIMELINE_POST / BLOG_POST / TODO / SCHEDULE / SURVEY）。
     */
    @Column(length = 30)
    private String preferredChannel;

    /**
     * デフォルトテンプレートフラグ。スコープごとに 1 件のみ true が許容される。
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    /**
     * 作成者ユーザー ID（退会時は NULL）。
     */
    @Column
    private Long createdBy;

    /** テンプレートを更新する。 */
    public void update(String name, String targetRole, String targetTeamIds,
                       String preferredChannel, boolean isDefault) {
        this.name = name;
        this.targetRole = targetRole;
        this.targetTeamIds = targetTeamIds;
        this.preferredChannel = preferredChannel;
        this.isDefault = isDefault;
    }
}
