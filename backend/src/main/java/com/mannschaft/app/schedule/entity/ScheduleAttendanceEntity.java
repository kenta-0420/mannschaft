package com.mannschaft.app.schedule.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.schedule.AttendanceStatus;
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

import java.time.LocalDateTime;

/**
 * スケジュール出欠エンティティ。各ユーザーの出欠回答を管理する。
 */
@Entity
@Table(name = "schedule_attendances")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ScheduleAttendanceEntity extends BaseEntity {

    @Column(nullable = false)
    private Long scheduleId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttendanceStatus status;

    @Column(length = 500)
    private String comment;

    private LocalDateTime respondedAt;

    @Column(name = "is_ranch_response_history_known", nullable = false)
    @org.hibernate.annotations.ColumnDefault("0")
    @Builder.Default
    private Boolean ranchResponseHistoryKnown = true;

    @Column(name = "is_ranch_self_response_observed", nullable = false)
    @org.hibernate.annotations.ColumnDefault("0")
    @Builder.Default
    private Boolean ranchSelfResponseObserved = false;

    @Column(name = "ranch_first_self_at", columnDefinition = "DATETIME(6)")
    private java.time.Instant ranchFirstSelfAt;

    @Column(name = "ranch_first_self_user_id", columnDefinition = "BIGINT UNSIGNED")
    private Long ranchFirstSelfUserId;

    @Column(name = "is_proxy_input", nullable = false, columnDefinition = "TINYINT(1) DEFAULT 0")
    @Builder.Default
    private Boolean isProxyInput = false;

    @Column(name = "proxy_input_record_id")
    private Long proxyInputRecordId;

    /**
     * 出欠を回答する。初回回答時のみ respondedAt をセットする。
     *
     * @param newStatus 新しい出欠ステータス
     * @param comment   コメント（nullable）
     */
    public void respond(AttendanceStatus newStatus, String comment) {
        if (newStatus != AttendanceStatus.UNDECIDED) ranchSelfResponseObserved = true;
        applyResponse(newStatus, comment);
    }

    /** 代理回答は本人初回の証拠を消費しない。報酬資格も生成しない。 */
    public void respondProxy(AttendanceStatus newStatus, String comment) {
        applyResponse(newStatus, comment);
    }

    /** ACTIVE本人lockを保持する源writerだけが、通常回答と同じUPDATEへ資格を固定する。 */
    public boolean freezeRanchFirstSelfResponse(java.time.Instant at, Long actor) {
        boolean first = Boolean.TRUE.equals(ranchResponseHistoryKnown)
                && !Boolean.TRUE.equals(ranchSelfResponseObserved) && actor.equals(userId);
        ranchSelfResponseObserved = true;
        if (first) { ranchFirstSelfAt = at; ranchFirstSelfUserId = actor; }
        return first;
    }

    private void applyResponse(AttendanceStatus newStatus, String comment) {
        this.status = newStatus;
        this.comment = comment;
        if (this.respondedAt == null) {
            this.respondedAt = LocalDateTime.now();
        }
    }
}
