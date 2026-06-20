package com.mannschaft.app.reflection.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.reflection.RecallSelfRating;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 想起テスト記録（F06.5・§2.4）。
 *
 * <p>1エントリにつき複数 recall が積み上がる。保存＝開示で {@code revealedAt} を記録する（AC-7）。
 * entry_id は同一 reflection ドメインのため FK＋CASCADE がある（DDL 側）。soft delete なし（履歴保持）。</p>
 */
@Entity
@Table(name = "recall_attempts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class RecallAttemptEntity extends UuidV7Entity {

    @Column(name = "entry_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID entryId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "recall_date", nullable = false)
    private LocalDate recallDate;

    @Column(name = "recalled_content", nullable = false, columnDefinition = "JSON")
    private String recalledContent;

    @Enumerated(EnumType.STRING)
    @Column(name = "self_rating", nullable = false, length = 12)
    private RecallSelfRating selfRating;

    @Column(name = "revealed_at")
    private LocalDateTime revealedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    /**
     * 開示時刻を記録する（保存＝開示・§3.2）。
     */
    public void markRevealed(LocalDateTime revealedAt) {
        this.revealedAt = revealedAt;
    }
}
