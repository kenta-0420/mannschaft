package com.mannschaft.app.reflection.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.reflection.ReflectionReminderKind;
import com.mannschaft.app.reflection.ReflectionReminderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 間隔反復スケジュール（F06.5・§2.5）。
 *
 * <p>バッチ走査テーブル。entry_id（SPACED）/ theme_id（PRE_EXAM）の多態のため、
 * 同一ドメインでも FK を張らず ID 参照とする（§2.5）。孤児はバッチ側で CANCELLED 化（fail-safe）。
 * status 遷移（PENDING→SENT）で二重送信を防止する（AC-10）。</p>
 */
@Entity
@Table(name = "reflection_spaced_reminders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class ReflectionSpacedReminderEntity extends UuidV7Entity {

    @Column(name = "entry_id", columnDefinition = "BINARY(16)")
    private UUID entryId;

    @Column(name = "theme_id", columnDefinition = "BINARY(16)")
    private UUID themeId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "remind_at", nullable = false)
    private LocalDateTime remindAt;

    @Column(name = "interval_days")
    private Integer intervalDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 12)
    private ReflectionReminderKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    @Builder.Default
    private ReflectionReminderStatus status = ReflectionReminderStatus.PENDING;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    /**
     * 送信済みに遷移する（status=SENT, sent_at=now）。二重送信防止（AC-10）。
     */
    public void markAsSent() {
        this.status = ReflectionReminderStatus.SENT;
        this.sentAt = LocalDateTime.now();
    }

    /**
     * キャンセル済みに遷移する（親削除・再生成・過去日ガード・孤児 fail-safe）。
     */
    public void cancel() {
        this.status = ReflectionReminderStatus.CANCELLED;
    }
}
