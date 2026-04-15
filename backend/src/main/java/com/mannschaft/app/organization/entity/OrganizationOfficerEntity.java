package com.mannschaft.app.organization.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 組織役員エンティティ。
 */
@Entity
@Table(name = "organization_officers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class OrganizationOfficerEntity extends BaseEntity {

    @Column(nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false)
    private Integer displayOrder;

    @Column(nullable = false)
    private Boolean isVisible;

    public void updateDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }

    public void updateVisibility(boolean isVisible) {
        this.isVisible = isVisible;
    }

    public void update(String name, String title, boolean isVisible) {
        this.name = name;
        this.title = title;
        this.isVisible = isVisible;
    }
}
