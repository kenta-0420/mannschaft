package com.mannschaft.app.knowledgebase.entity;

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
 * ナレッジベーステンプレートエンティティ。
 */
@Entity
@Table(name = "kb_templates")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class KbTemplateEntity extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String scopeType;

    @Column
    private Long scopeId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String body;

    @Column(length = 50)
    private String icon;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isSystem = false;

    private Long createdBy;

    @Version
    private Long version;

    private LocalDateTime deletedAt;

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
