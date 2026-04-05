package com.mannschaft.app.admin.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 管理者アクションテンプレートエンティティ。論理削除あり。
 */
@Entity
@Table(name = "admin_action_templates")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AdminActionTemplateEntity extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 20)
    private String actionType;

    @Column(length = 30)
    private String reason;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String templateText;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    @Column(nullable = false)
    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * テンプレートを更新する。
     *
     * @param name         テンプレート名
     * @param actionType   アクション種別
     * @param reason       理由
     * @param templateText テンプレート文
     * @param isDefault    デフォルトフラグ
     */
    public void update(String name, String actionType, String reason,
                       String templateText, Boolean isDefault) {
        this.name = name;
        this.actionType = actionType;
        this.reason = reason;
        this.templateText = templateText;
        this.isDefault = isDefault;
    }

    /**
     * 論理削除する。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
