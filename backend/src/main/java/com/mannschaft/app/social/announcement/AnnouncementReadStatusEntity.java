package com.mannschaft.app.social.announcement;

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
 * お知らせ既読管理エンティティ（F02.6）。
 *
 * <p>
 * ユーザーごとのお知らせ既読トラッキングテーブル {@code announcement_read_status} のエンティティ。
 * ウィジェットの「未読バッジ」「既読後のリストでの薄表示」などの UX 機能で使用する。
 * </p>
 *
 * <p>
 * <b>制約</b>:
 * <ul>
 *   <li>{@code (announcement_feed_id, user_id)} にユニーク制約があり重複既読は防止される</li>
 *   <li>親の {@code announcement_feeds} レコードが削除された場合 CASCADE 削除される</li>
 *   <li>ユーザーが退会した場合も CASCADE 削除される</li>
 *   <li>保持期間は 90 日（バッチで古いお知らせの既読レコードを物理削除）</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>注</b>: createdAt / updatedAt が不要なため {@link com.mannschaft.app.common.BaseEntity} は継承しない。
 * </p>
 */
@Entity
@Table(name = "announcement_read_status")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AnnouncementReadStatusEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 既読したお知らせフィードの ID。
     */
    @Column(nullable = false)
    private Long announcementFeedId;

    /**
     * 既読したユーザーの ID。
     */
    @Column(nullable = false)
    private Long userId;

    /**
     * 既読日時。INSERT 時に自動設定される。
     */
    @Column(nullable = false)
    private LocalDateTime readAt;

    /**
     * readAt が未設定の場合に現在時刻で補完する。
     */
    @PrePersist
    protected void onCreate() {
        if (this.readAt == null) {
            this.readAt = LocalDateTime.now();
        }
    }
}
