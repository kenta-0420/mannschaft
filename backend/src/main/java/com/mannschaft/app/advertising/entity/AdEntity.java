package com.mannschaft.app.advertising.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
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
    @Builder.Default
    private AdStatus status = AdStatus.DRAFT;

    // ─── F09.19.1 placement + バナー表示属性（V144.001。骨格 — 業務ロジックは出陣で実装） ───

    /** 掲載面（AdPlacement）。クリエイティブはサイズが placement 依存のため ads 単位。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private com.mannschaft.app.advertising.AdPlacement placement;

    /** バナー幅 px（NULL: FE の placement 既定サイズ）。 */
    private Integer width;

    /** バナー高さ px。 */
    private Integer height;

    /** 代替テキスト（NULL: title を代用）。 */
    @Column(length = 200)
    private String altText;

    public enum AdStatus {
        DRAFT, ACTIVE, PAUSED, ENDED
    }

    /**
     * クリエイティブ情報を更新する。null の場合は現在の値を保持する。
     * placement / width / height / altText も null なら変更なし（F09.19.1 §5.2）。
     */
    public void updateCreative(String title, String imageUrl, String destinationUrl,
                               com.mannschaft.app.advertising.AdPlacement placement,
                               Integer width, Integer height, String altText) {
        if (title != null) {
            this.title = title;
        }
        if (imageUrl != null) {
            this.imageUrl = imageUrl;
        }
        if (destinationUrl != null) {
            this.destinationUrl = destinationUrl;
        }
        if (placement != null) {
            this.placement = placement;
        }
        if (width != null) {
            this.width = width;
        }
        if (height != null) {
            this.height = height;
        }
        if (altText != null) {
            this.altText = altText;
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
