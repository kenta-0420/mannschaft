package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 村ニュースレター配信履歴（F17.1 Phase 3-β-E）。
 *
 * <p>1 回の配信バッチ実行ごとに 1 レコード。recipient_count / success_count /
 * failure_count を集計する。</p>
 *
 * <h2>原則準拠</h2>
 * <ul>
 *   <li>原則6: 新規テーブルのため UUIDv7 を採用。</li>
 * </ul>
 */
@Entity
@Table(name = "village_newsletter_send_logs")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageNewsletterSendLogEntity extends UuidV7Entity {

    /** FK → village_newsletters.id（同一ドメイン CASCADE）。 */
    @Column(name = "newsletter_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID newsletterId;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @Column(name = "recipient_count", nullable = false)
    private Integer recipientCount;

    @Column(name = "success_count", nullable = false)
    private Integer successCount;

    @Column(name = "failure_count", nullable = false)
    private Integer failureCount;

    @PrePersist
    protected void onCreate() {
        if (this.sentAt == null) {
            this.sentAt = LocalDateTime.now();
        }
        if (this.recipientCount == null) {
            this.recipientCount = 0;
        }
        if (this.successCount == null) {
            this.successCount = 0;
        }
        if (this.failureCount == null) {
            this.failureCount = 0;
        }
    }
}
