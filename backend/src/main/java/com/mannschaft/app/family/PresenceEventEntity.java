package com.mannschaft.app.family;

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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * プレゼンスイベントエンティティ。帰ったよ通知・お出かけ連絡の履歴を保持する。
 */
@Entity
@Table(name = "presence_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class PresenceEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventType eventType;

    @Column(length = 100)
    private String message;

    @Column(length = 200)
    private String destination;

    private LocalDateTime expectedReturnAt;

    private LocalDateTime returnedAt;

    @Column(nullable = false)
    private Integer overdueLevel;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.overdueLevel == null) {
            this.overdueLevel = 0;
        }
    }

    /**
     * 帰宅時刻を記録する（GOING_OUTの自動クローズ用）。
     */
    public void markReturned() {
        this.returnedAt = LocalDateTime.now();
    }

    /**
     * 遅延通知レベルを更新する。
     *
     * @param level 新しい遅延レベル
     */
    public void updateOverdueLevel(int level) {
        this.overdueLevel = level;
    }
}
