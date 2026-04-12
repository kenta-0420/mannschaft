package com.mannschaft.app.notification.confirmable.entity;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.membership.ScopeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
 * F04.9 確認通知テンプレートエンティティ。
 *
 * <p>繰り返し使用する確認通知の雛形。
 * 論理削除（{@code deleted_at}）をサポートし、削除後もIDで参照可能。</p>
 */
@Entity
@Table(name = "confirmable_notification_templates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ConfirmableNotificationTemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** スコープ種別（TEAM / ORGANIZATION） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScopeType scopeType;

    /** スコープID（チームIDまたは組織ID） */
    @Column(nullable = false)
    private Long scopeId;

    /** 管理用テンプレート名（最大100文字） */
    @Column(nullable = false, length = 100)
    private String name;

    /** テンプレートタイトル（最大200文字） */
    @Column(nullable = false, length = 200)
    private String title;

    /** テンプレート本文（任意） */
    @Column(columnDefinition = "TEXT")
    private String body;

    /** デフォルト優先度（NORMAL / HIGH / URGENT） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private ConfirmableNotificationPriority defaultPriority = ConfirmableNotificationPriority.NORMAL;

    /** テンプレート作成者（退会時 NULL に設定） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private UserEntity createdBy;

    /**
     * 論理削除日時。NULL の場合は有効なテンプレート。
     * {@code softDelete()} で設定される。
     */
    @Column
    private LocalDateTime deletedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
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

    // -------------------------------------------------------------------------
    // ドメインメソッド
    // -------------------------------------------------------------------------

    /**
     * テンプレートを論理削除する。
     *
     * <p>削除後もIDによる参照は可能（確認通知の {@code template_id} 外部参照保護）。</p>
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 論理削除済みかどうかを判定する。
     *
     * @return 削除済みの場合 true
     */
    public boolean isDeleted() {
        return this.deletedAt != null;
    }
}
