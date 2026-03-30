package com.mannschaft.app.incident.entity;

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
 * インシデントエンティティ。
 * status/priority は String で保持し、Enum への変換は Service 層で行う。
 */
@Entity
@Table(name = "incidents")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class IncidentEntity extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    private Long categoryId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 30)
    @Builder.Default
    private String status = "REPORTED";

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String priority = "MEDIUM";

    private LocalDateTime slaDeadline;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isSlaBreached = false;

    @Column(nullable = false)
    private Long reportedBy;

    private Long workflowRequestId;

    @Version
    private Long version;

    private LocalDateTime deletedAt;

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * ステータスを変更する。
     */
    public void changeStatus(String newStatus) {
        this.status = newStatus;
    }

    /**
     * SLA超過フラグを立てる。
     */
    public void markSlaBreached() {
        this.isSlaBreached = true;
    }

    /**
     * SLA期限を設定する。
     */
    public void setSlaDeadline(LocalDateTime deadline) {
        this.slaDeadline = deadline;
    }

    /**
     * タイトルと説明を更新する。
     */
    public void updateDetails(String title, String description) {
        this.title = title;
        this.description = description;
    }

    /**
     * 優先度を変更する。
     */
    public void changePriority(String newPriority) {
        this.priority = newPriority;
    }

    /**
     * カテゴリを設定する。
     */
    public void assignCategory(Long categoryId) {
        this.categoryId = categoryId;
    }
}
