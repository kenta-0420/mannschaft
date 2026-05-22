package com.mannschaft.app.advertising.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 広告インプレッションエンティティ。
 * ad_impressions テーブルに対応する不変なイベントレコード。
 */
@Entity
@Table(name = "ad_impressions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AdImpressionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long adId;

    @Column(nullable = false)
    private Long campaignId;

    /** 未ログインユーザーのインプレッションは NULL */
    @Column
    private Long userId;

    @Column(nullable = false)
    private LocalDateTime occurredAt;

    @PrePersist
    protected void onCreate() {
        if (this.occurredAt == null) {
            this.occurredAt = LocalDateTime.now();
        }
    }

    /**
     * インプレッション記録を生成するファクトリメソッド。
     *
     * @param adId       広告ID
     * @param campaignId キャンペーンID
     * @param userId     ユーザーID（未ログインの場合は null）
     * @return 新規 AdImpressionEntity
     */
    public static AdImpressionEntity create(Long adId, Long campaignId, Long userId) {
        Objects.requireNonNull(adId, "adId is required");
        Objects.requireNonNull(campaignId, "campaignId is required");
        return AdImpressionEntity.builder()
                .adId(adId)
                .campaignId(campaignId)
                .userId(userId)
                .occurredAt(LocalDateTime.now())
                .build();
    }
}
