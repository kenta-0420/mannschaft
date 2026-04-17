package com.mannschaft.app.team.entity;

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
 * チームマスターエンティティ。チームの基本情報・公開設定を管理する。
 */
@Entity
@Table(name = "teams")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TeamEntity extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 100)
    private String nameKana;

    @Column(length = 50)
    private String nickname1;

    @Column(length = 50)
    private String nickname2;

    @Column(length = 30)
    private String template;

    @Column(length = 20)
    private String prefecture;

    @Column(length = 50)
    private String city;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Visibility visibility;

    @Column(nullable = false)
    private Boolean supporterEnabled;

    @Version
    private Long version;

    private LocalDateTime archivedAt;

    private LocalDateTime deletedAt;

    // --- F01.2 拡張プロフィールフィールド ---

    @Column(name = "icon_url", length = 500)
    private String iconUrl;

    @Column(name = "banner_url", length = 500)
    private String bannerUrl;

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
     * チーム公開範囲
     */
    public enum Visibility {
        PUBLIC,
        ORGANIZATION_ONLY,
        PRIVATE
    }

    /**
     * チームをアーカイブする。
     */
    public void archive() {
        this.archivedAt = LocalDateTime.now();
    }

    /**
     * チームのアーカイブを解除する。
     */
    public void unarchive() {
        this.archivedAt = null;
    }

    /**
     * チームを論理削除する。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * チームの論理削除を取り消す。
     */
    public void restore() {
        this.deletedAt = null;
    }

    /**
     * アイコン画像URLを更新する。
     */
    public void updateIconUrl(String iconUrl) {
        this.iconUrl = iconUrl;
    }

    /**
     * バナー画像URLを更新する。
     */
    public void updateBannerUrl(String bannerUrl) {
        this.bannerUrl = bannerUrl;
    }
}
