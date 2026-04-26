package com.mannschaft.app.dashboard.entity;

import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.ScopeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * F02.2.1: ダッシュボードウィジェットのロール別可視性設定エンティティ。
 *
 * スコープ（チーム／組織）×ウィジェットごとに、最低必要ロール（min_role）を管理する。
 * レコードがないウィジェットはアプリ層のデフォルト値（WidgetDefaultMinRoleMap）が適用されるため、
 * 全件 INSERT は行わず、デフォルト値と異なる場合のみ DB レコードが作られる遅延作成方式。
 *
 * <p>scope_type は {@link ScopeType#TEAM} または {@link ScopeType#ORGANIZATION} のみ。
 * {@link ScopeType#PERSONAL} は本機能の対象外（個人ダッシュボードはロール制御不要）。</p>
 *
 * <p>設計書: docs/features/F02.2.1_dashboard_widget_role_visibility.md §3</p>
 */
@Entity
@Table(name = "dashboard_widget_role_visibility")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class DashboardWidgetRoleVisibilityEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private ScopeType scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Column(name = "widget_key", nullable = false, length = 50)
    private String widgetKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "min_role", nullable = false, length = 20)
    private MinRole minRole;

    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 最低必要ロールを変更する。最終更新者も同時に記録する。
     *
     * @param minRole   新しい最低必要ロール
     * @param updatedBy 更新者ユーザーID
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
