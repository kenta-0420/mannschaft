package com.mannschaft.app.schedule.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * スケジュール・Googleイベントマッピングエンティティ。
 */
@Entity
@Table(name = "user_schedule_google_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class UserScheduleGoogleEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long scheduleId;

    @Column(nullable = false)
    private String googleEventId;

    @Column(nullable = false)
    private LocalDateTime lastSyncedAt;

    /**
     * Google カレンダーとの同期方向。
     * Phase 4 で双方向同期を導入するにあたり追加。
     * @Builder.Default 必須（NULL 挿入バグ回避）。
     */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "sync_direction", nullable = false, length = 12)
    private SyncDirection syncDirection = SyncDirection.PUSH_ONLY;

    /**
     * Google カレンダーイベントの ETag。
     * 双方向同期時に条件付きリクエスト（IF-NONE-MATCH）で使用する。
     */
    @Column(name = "google_etag", length = 255)
    private String googleEtag;

    /**
     * 同期日時を現在時刻に更新する。
     */
    public void updateSyncedAt() {
        this.lastSyncedAt = LocalDateTime.now();
    }

    /**
     * Google イベントの ETag を更新する（同期後に呼び出す）。
     */
    public void updateGoogleEtag(String etag) {
        this.googleEtag = etag;
    }

    /**
     * 同期方向を双方向に変更する（Phase 4 でユーザーが双方向同期を有効化した際に呼び出す）。
     */
    public void updateSyncDirectionToBidirectional() {
        this.syncDirection = SyncDirection.BIDIRECTIONAL;
    }
}
