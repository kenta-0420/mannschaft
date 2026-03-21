package com.mannschaft.app.workflow.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * ワークフローテンプレートエンティティ。承認フローの雛型を管理する。
 */
@Entity
@Table(name = "workflow_templates")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class WorkflowTemplateEntity extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(length = 50)
    private String icon;

    @Column(length = 7)
    private String color;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isSealRequired = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    private Long createdBy;

    @Version
    private Long version;

    private LocalDateTime deletedAt;

    /**
     * テンプレートを有効化する。
     */
    public void activate() {
        this.isActive = true;
    }

    /**
     * テンプレートを無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * テンプレート情報を更新する。
     *
     * @param name        テンプレート名
     * @param description 説明
     * @param icon        アイコン
     * @param color       色
     * @param isSealRequired 電子印鑑必須フラグ
     * @param sortOrder   並び順
     */
    public void update(String name, String description, String icon, String color,
                       Boolean isSealRequired, Integer sortOrder) {
        this.name = name;
        this.description = description;
        this.icon = icon;
        this.color = color;
        this.isSealRequired = isSealRequired;
        this.sortOrder = sortOrder;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
