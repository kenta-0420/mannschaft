package com.mannschaft.app.member.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.member.MemberSubtabKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * CMP-260919-1140 Phase 1: メンバー統合画面サブタブのロール別可視性設定エンティティ。
 *
 * <p>スコープ（チーム／組織）×サブタブ（一覧／紹介）ごとに、最低必要ロール（min_role）を管理する。
 * レコードがないサブタブはアプリ層のデフォルト値（{@link com.mannschaft.app.member.MemberSubtabDefaultMinRoleMap}）
 * が適用されるため、全件 INSERT は行わず、デフォルト値と異なる場合のみ DB レコードが作られる遅延作成方式
 * （{@link com.mannschaft.app.dashboard.entity.DashboardWidgetRoleVisibilityEntity} と同一パターン。
 * ただし主キーは DB 設計原則 #6 に従い新規テーブルとして UUIDv7 を採用する）。</p>
 *
 * <p>設計書: docs/features/F06.6_member_subtab_visibility.md §3</p>
 */
@Entity
@Table(name = "member_subtab_role_visibility")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class MemberSubtabRoleVisibilityEntity extends UuidV7Entity {

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private ScopeType scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Column(name = "subtab_key", nullable = false, length = 30)
    private String subtabKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "min_role", nullable = false, length = 20)
    private MinRole minRole;

    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public MemberSubtabKey resolveSubtabKey() {
        return MemberSubtabKey.fromDbValue(this.subtabKey);
    }

    /**
     * 最低必要ロールを変更する。最終更新者も同時に記録する。
     */
    public void changeMinRole(MinRole minRole, Long updatedBy) {
        if (minRole == null) {
            throw new IllegalArgumentException("MinRole must not be null");
        }
        if (updatedBy == null) {
            throw new IllegalArgumentException("updatedBy must not be null");
        }
        this.minRole = minRole;
        this.updatedBy = updatedBy;
    }
}
