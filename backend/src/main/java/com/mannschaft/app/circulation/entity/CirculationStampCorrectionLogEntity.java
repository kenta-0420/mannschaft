package com.mannschaft.app.circulation.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * 押印訂正履歴エンティティ。
 *
 * <p>F05.2 Phase 11 第三陣 3-B で追加。受信者本人が押印を訂正した際の
 * 訂正前スナップショットを保持する（is_flipped=true の逆さまハンコ訂正のほか、
 * 訂正後 24h 以内の任意訂正にも対応）。</p>
 *
 * <p>CLAUDE.md 原則 6: 新規テーブルのため UUIDv7 主キー。
 * 親 {@link CirculationRecipientEntity} とは同一ドメインのため CASCADE 削除可。</p>
 */
@Entity
@Table(name = "circulation_stamp_correction_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class CirculationStampCorrectionLogEntity extends UuidV7Entity {

    @Column(nullable = false)
    private Long recipientId;

    @Column(nullable = false)
    private Long documentId;

    @Column(nullable = false)
    private Long correctedBy;

    private Long originalSealId;

    @Column(length = 20)
    private String originalSealVariant;

    private Short originalTiltAngle;

    @Column(nullable = false)
    @Builder.Default
    private Boolean originalIsFlipped = false;

    @Column(length = 255)
    private String reason;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
