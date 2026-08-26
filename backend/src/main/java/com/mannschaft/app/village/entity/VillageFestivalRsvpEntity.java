package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.village.entity.enums.VillageFestivalRsvpStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * お祭りの参加表明（RSVP）エンティティ（F17.2 Wave2 ③お祭りの参加レイヤー・設計書 §5.2）。
 *
 * <p>祭に対して村人が自分の参加表明（{@link VillageFestivalRsvpStatus#GOING GOING}／
 * {@link VillageFestivalRsvpStatus#MAYBE MAYBE}）を upsert する。
 * {@code (festival_id, user_id)} の UNIQUE 制約で「1祭×1村人=1行」を DB レベルで
 * 保証する（設計書 §5.2・upsert 実装方式は §4.4.1）。</p>
 *
 * <p><b>ABSENT を持たない</b>: 不参加は「無回答」と同じ扱い（レコードが無い＝答えていない）。
 * これにより欠席者一覧・欠席率が構造的に作れず、村人を追い立てないガードレール（§10）を
 * DB レベルで担保する。</p>
 *
 * <p>{@code festival_id} は同一ドメイン（village）内の参照だが、原則1に従い
 * FK は張らずインデックスのみで整合を保証する。{@code role_label} は役割の自由記述ラベル
 * （例「出店係」「受付」・NULL=役割なし・設計書 §5.3）。</p>
 */
@Entity
@Table(name = "village_festival_rsvps")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageFestivalRsvpEntity extends UuidV7Entity {

    /** → village_festivals.id（同一ドメイン・FK非付与/index・原則1） */
    @Column(name = "festival_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID festivalId;

    /** 参加表明した村人（FK非付与・原則1） */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private VillageFestivalRsvpStatus status;

    /** 役割の自由記述ラベル（例「出店係」「受付」）。NULL=役割なし（設計書 §5.3） */
    @Column(name = "role_label", length = 60)
    private String roleLabel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

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
}
