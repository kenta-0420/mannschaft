package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * お気に入り村ピン留めエンティティ（F17.1 Phase 1）。
 *
 * <p>ピン上限は 30 件（ソフト警告）。退会時は物理削除。</p>
 */
@Entity
@Table(name = "user_village_pins")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class UserVillagePinEntity extends UuidV7Entity {

    /** ユーザーID（FK 張らない／原則1） */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** FK → villages.id（同一ドメイン・CASCADE） */
    @Column(name = "village_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID villageId;

    @Column(name = "sort_order", nullable = false)
    private Long sortOrder;

    @Column(name = "pinned_at", nullable = false)
    private LocalDateTime pinnedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.pinnedAt == null) {
            this.pinnedAt = now;
        }
        if (this.sortOrder == null) {
            this.sortOrder = 0L;
        }
    }
}
