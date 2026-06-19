package com.mannschaft.app.advertising.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 広告エンティティ。
 */
@Entity
@Table(name = "ads")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder
public class AdEntity extends BaseEntity {

    @Column(nullable = false)
    private Long campaignId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 500)
    private String imageUrl;

    @Column(nullable = false, length = 500)
    private String destinationUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @SuperBuilder.Default
    private AdStatus status = AdStatus.DRAFT;

    public enum AdStatus {
        DRAFT, ACTIVE, PAUSED, ENDED
    }

    /**
     * クリエイティブ情報を更新する。null の場合は現在の値を保持する。
     */
    public void updateCreative(String title, String imageUrl, String destinationUrl) {
        if (title != null) {
            this.title = title;
        }
        if (imageUrl != null) {
            this.imageUrl = imageUrl;
        }
        if (destinationUrl != null) {
            this.destinationUrl = destinationUrl;
        }
    }

    /**
     * 論理削除（ENDED状態にする）。
     */
    public void softDelete() {
        this.status = AdStatus.ENDED;
    }

    /**
     * 審査承認（ACTIVE状態にする）。
     */
    public void approve() {
        this.status = AdStatus.ACTIVE;
    }

    /**
     * 審査却下（ENDED状態にする）。
     */
    public void reject() {
        this.status = AdStatus.ENDED;
    }
}
