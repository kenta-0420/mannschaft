package com.mannschaft.app.organization.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 組織マスターエンティティ。組織の基本情報・公開設定・階層構造を管理する。
 */
@Entity
@Table(name = "organizations")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class OrganizationEntity extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 100)
    private String nameKana;

    @Column(length = 50)
    private String nickname1;

    @Column(length = 50)
    private String nickname2;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrgType orgType;

    private Long parentOrganizationId;

    @Column(length = 20)
    private String prefecture;

    @Column(length = 50)
    private String city;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Visibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HierarchyVisibility hierarchyVisibility;

    @Column(nullable = false)
    private Boolean supporterEnabled;

    @Version
    private Long version;

    private LocalDateTime archivedAt;

    private LocalDateTime deletedAt;

    // --- F01.2 拡張プロフィールフィールド ---

    @Column(length = 512)
    private String homepageUrl;

    private LocalDate establishedDate;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private com.mannschaft.app.organization.EstablishedDatePrecision establishedDatePrecision;

    @Column(columnDefinition = "TEXT")
    private String philosophy;

    @Convert(converter = com.mannschaft.app.organization.ProfileVisibilityConverter.class)
    @Column(columnDefinition = "JSON")
    private com.mannschaft.app.organization.ProfileVisibility profileVisibility;

    /**
     * 組織種別
     */
    public enum OrgType {
        SCHOOL,
        COMPANY,
        NPO,
        COMMUNITY,
        GOVERNMENT,
        OTHER
    }

    /**
     * 公開範囲
     */
    public enum Visibility {
        PUBLIC,
        PRIVATE
    }

    /**
     * 階層公開範囲
     */
    public enum HierarchyVisibility {
        NONE,
        BASIC,
        FULL
    }

    /**
     * 組織をアーカイブする。
     */
    public void archive() {
        this.archivedAt = LocalDateTime.now();
    }

    /**
     * 組織のアーカイブを解除する。
     */
    public void unarchive() {
        this.archivedAt = null;
    }

    /**
     * 組織を論理削除する。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 組織の論理削除を取り消す。
     */
    public void restore() {
        this.deletedAt = null;
    }
}
