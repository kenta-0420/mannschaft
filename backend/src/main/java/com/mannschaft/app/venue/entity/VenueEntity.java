package com.mannschaft.app.venue.entity;

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
 * 施設マスタエンティティ（Google Places APIで正規化された場所情報）。
 */
@Entity
@Table(name = "venues")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class VenueEntity extends BaseEntity {

    @Column(length = 300, unique = true)
    private String googlePlaceId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 500)
    private String address;

    private java.math.BigDecimal latitude;

    private java.math.BigDecimal longitude;

    @Column(length = 20)
    private String prefecture;

    @Column(length = 50)
    private String city;

    @Column(length = 50)
    private String category;

    @Column(length = 30)
    private String phoneNumber;

    @Column(length = 500)
    private String websiteUrl;

    @Column(nullable = false)
    @Builder.Default
    private Integer usageCount = 0;

    /**
     * 利用回数をインクリメントする。
     */
    public void incrementUsageCount() {
        this.usageCount++;
    }

    /**
     * Google Places APIから取得した情報で更新する。
     */
    public void updateFromPlaces(String name, String address, java.math.BigDecimal latitude,
                                  java.math.BigDecimal longitude, String prefecture, String city,
                                  String category, String phoneNumber, String websiteUrl) {
        this.name = name;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.prefecture = prefecture;
        this.city = city;
        this.category = category;
        this.phoneNumber = phoneNumber;
        this.websiteUrl = websiteUrl;
    }
}
