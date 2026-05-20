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

/**
 * 広告クリックエンティティ。
 * ad_clicks テーブルに対応する不変なイベントレコード。
 */
@Entity
@Table(name = "ad_clicks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AdClickEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long adId;

    @Column(nullable = false)
    private Long campaignId;

    /** インプレッションなしの直接クリックは NULL */
    @Column
    private Long impressionId;

    /** 未ログインユーザーのクリックは NULL */
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
     * クリック記録を生成するファクトリメソッド。
     *
     * @param adId         広告ID
     * @param campaignId   キャンペーンID
     * @param impressionId インプレッションID（直接クリックの場合は null）
     * @param userId       ユーザーID（未ログインの場合は null）
     * @return 新規 AdClickEntity
     */
    public static AdClickEntity create(Long adId, Long campaignId, Long impressionId, Long userId) {
        return AdClickEntity.builder()
                .adId(adId)
                .campaignId(campaignId)
                .impressionId(impressionId)
                .userId(userId)
                .occurredAt(LocalDateTime.now())
                .build();
    }
}
