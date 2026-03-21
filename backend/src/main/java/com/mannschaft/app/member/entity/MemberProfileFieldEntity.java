package com.mannschaft.app.member.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.member.FieldType;
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
 * プロフィール拡張フィールド定義エンティティ。チーム/組織単位でカスタムフィールドを定義する。
 */
@Entity
@Table(name = "member_profile_fields")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class MemberProfileFieldEntity extends BaseEntity {

    private Long teamId;

    private Long organizationId;

    @Column(nullable = false, length = 100)
    private String fieldName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private FieldType fieldType = FieldType.TEXT;

    @Column(columnDefinition = "JSON")
    private String options;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isRequired = false;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /**
     * フィールド定義を更新する。
     */
    public void update(String fieldName, FieldType fieldType, String options,
                       Boolean isRequired, Integer sortOrder) {
        this.fieldName = fieldName;
        this.fieldType = fieldType;
        this.options = options;
        this.isRequired = isRequired;
        this.sortOrder = sortOrder;
    }

    /**
     * フィールドを無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }
}
