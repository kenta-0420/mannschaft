package com.mannschaft.app.ranch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import com.mannschaft.app.ranch.*;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** ranch_ownersの本人スコープ永続骨格。 */
@Entity
@Table(name = "ranch_owners")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RanchOwnerEntity extends RanchEntity {
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private ParticipationStatus status;
    @Column(name = "balance", nullable = false)
    private long balance;
    @Column(name = "view_mode", nullable = false, length = 20)
    private String viewMode;
    @Column(name = "render_style", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private RenderStyle renderStyle;
    @Column(name = "motion_mode", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private MotionMode motionMode;
    @Column(name = "is_sound_enabled", nullable = false)
    private boolean soundEnabled;
    @Column(name = "sound_volume", nullable = false)
    private int soundVolume;
    @Column(name = "version", nullable = false)
    private long version;

    public void updateSettings(RenderStyle nextRenderStyle, MotionMode nextMotionMode,
                               boolean nextSoundEnabled, int nextSoundVolume) {
        if (nextRenderStyle == null || nextMotionMode == null
                || nextSoundVolume < 0 || nextSoundVolume > 100) {
            throw new IllegalArgumentException("設定値が不正です");
        }
        renderStyle = nextRenderStyle;
        motionMode = nextMotionMode;
        soundEnabled = nextSoundEnabled;
        soundVolume = nextSoundVolume;
        version = Math.addExact(version, 1);
    }

    public void pause() {
        if (status != ParticipationStatus.ACTIVE) {
            throw new IllegalStateException("ACTIVEの牧場だけ休止できます");
        }
        status = ParticipationStatus.PAUSED;
        version = Math.addExact(version, 1);
    }

    public void resume() {
        if (status != ParticipationStatus.PAUSED) {
            throw new IllegalStateException("PAUSEDの牧場だけ再開できます");
        }
        status = ParticipationStatus.ACTIVE;
        version = Math.addExact(version, 1);
    }

    public void advanceVersion() {
        version = Math.addExact(version, 1);
    }

    public void spend(long points) {
        if (points <= 0 || balance < points) {
            throw new IllegalArgumentException("購入ポイントが不足または不正です");
        }
        balance = Math.subtractExact(balance, points);
        advanceVersion();
    }

    public void credit(long points) {
        if (points <= 0) {
            throw new IllegalArgumentException("付与ポイントが不正です");
        }
        balance = Math.addExact(balance, points);
        advanceVersion();
    }
}
