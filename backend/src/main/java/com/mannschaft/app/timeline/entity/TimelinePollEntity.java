package com.mannschaft.app.timeline.entity;

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
 * タイムライン投票エンティティ。投稿に紐付く投票を管理する。
 */
@Entity
@Table(name = "timeline_polls")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TimelinePollEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long timelinePostId;

    @Column(nullable = false, length = 200)
    private String question;

    @Column(nullable = false)
    @Builder.Default
    private Integer totalVoteCount = 0;

    private LocalDateTime expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isClosed = false;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 投票を締め切る。
     */
    public void close() {
        this.isClosed = true;
    }

    /**
     * 総投票数をインクリメントする。
     */
    public void incrementVoteCount() {
        this.totalVoteCount++;
    }

    /**
     * 投票期限が過ぎているかを判定する。
     *
     * @return 期限超過の場合 true
     */
    public boolean isExpired() {
        return this.expiresAt != null && LocalDateTime.now().isAfter(this.expiresAt);
    }
}
