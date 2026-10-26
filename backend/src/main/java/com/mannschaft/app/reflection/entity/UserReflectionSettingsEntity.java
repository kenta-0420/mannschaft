package com.mannschaft.app.reflection.entity;

import com.mannschaft.app.reflection.ReflectionConstants;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * ユーザーごとの想起通知設定（F06.5・§2.7）。
 *
 * <p><b>UuidV7 を適用しない例外</b>（CLAUDE.md 原則6例外）: ユーザーごと1行のシングルトン的設定ゆえ
 * 自然キー（user_id）で十分。{@code user_blog_settings} と同方式。テナント非適用。</p>
 *
 * <p>未設定ユーザーは既定 {@link ReflectionConstants#DEFAULT_REMIND_HOUR} 時（行が無ければ既定値を使う）。</p>
 */
@Entity
@Table(name = "user_reflection_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class UserReflectionSettingsEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "remind_hour", nullable = false, columnDefinition = "TINYINT")
    @Builder.Default
    private Integer remindHour = ReflectionConstants.DEFAULT_REMIND_HOUR;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 想起通知時刻を更新する（直接ミューテート）。
     *
     * @param remindHour 0-23（範囲検証はサービス層／DB CHECK）
     */
    public void updateRemindHour(Integer remindHour) {
        if (remindHour != null) {
            this.remindHour = remindHour;
        }
    }
}
