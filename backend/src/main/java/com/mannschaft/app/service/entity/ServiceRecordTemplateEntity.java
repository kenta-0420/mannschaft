package com.mannschaft.app.service.entity;

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
 * 記録テンプレートエンティティ。
 */
@Entity
@Table(name = "service_record_templates")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ServiceRecordTemplateEntity extends BaseEntity {

    private Long teamId;

    private Long organizationId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 200)
    private String titleTemplate;

    @Column(columnDefinition = "TEXT")
    private String noteTemplate;

    private Integer defaultDurationMinutes;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * テンプレートを更新する。
     */
    public void update(String name, String titleTemplate, String noteTemplate,
                       Integer defaultDurationMinutes, Integer sortOrder) {
        this.name = name;
        this.titleTemplate = titleTemplate;
        this.noteTemplate = noteTemplate;
        this.defaultDurationMinutes = defaultDurationMinutes;
        this.sortOrder = sortOrder;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
