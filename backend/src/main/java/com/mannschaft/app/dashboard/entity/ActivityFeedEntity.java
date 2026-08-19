package com.mannschaft.app.dashboard.entity;

import com.mannschaft.app.dashboard.ActivityType;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.TargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * アクティビティフィードエンティティ。
 * 各機能の Service 層が ApplicationEvent を発行し、ActivityFeedEventListener が非同期で INSERT する。
 * 保持期間は30日。日次バッチ（AM 3:00）で古いレコードを物理削除する。
 */
@Entity
@Table(name = "activity_feed")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ActivityFeedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false)
    private Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ActivityType activityType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TargetType targetType;

    @Column(nullable = false)
    private Long targetId;

    @Column(nullable = false, length = 200)
    private String summary;

    /**
     * F03.18: 変更差分（JSON文字列）。SCHEDULE系活動のみ非NULL、既存種別は常にNULL。
     * DB列はJSON型だが、JPAマッピングは素の文字列として保持する（既存方針を踏襲）。
     */
    @Column(columnDefinition = "JSON")
    private String detail;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
