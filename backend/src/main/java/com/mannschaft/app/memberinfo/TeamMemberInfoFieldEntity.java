package com.mannschaft.app.memberinfo;

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

@Entity
@Table(name = "team_member_info_fields")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TeamMemberInfoFieldEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false, length = 100)
    private String fieldName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private MemberInfoFieldType fieldType = MemberInfoFieldType.TEXT;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isRequired = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isSensitive = false;

    @Column(nullable = true)
    private Integer refreshIntervalMonths;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /**
     * フィールド情報を更新する。
     * managed エンティティを直接ミューテートして id を保持したまま UPDATE を発行する。
     * （toBuilder().build() は継承フィールド id を引き継がず INSERT 化するため使用しない）
     */
    public void applyUpdate(String fieldName, MemberInfoFieldType fieldType, Boolean isRequired,
                            Boolean isSensitive, Integer refreshIntervalMonths, Integer sortOrder) {
        if (fieldName != null) this.fieldName = fieldName;
        if (fieldType != null) this.fieldType = fieldType;
        if (isRequired != null) this.isRequired = isRequired;
        if (isSensitive != null) this.isSensitive = isSensitive;
        if (refreshIntervalMonths != null) this.refreshIntervalMonths = refreshIntervalMonths;
        if (sortOrder != null) this.sortOrder = sortOrder;
    }

    /**
     * フィールドを無効化（論理削除）する。
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * 並び順を更新する。
     */
    public void updateSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
