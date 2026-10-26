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
 * 村ニュースレター opt-out レコード（F17.1 Phase 3-β-E）。
 *
 * <p>デフォルトは opt-in（村人は何もしなくても受信対象）。
 * このテーブルにレコードが「存在する」=「該当ユーザーは受信しない」。</p>
 *
 * <h2>原則準拠</h2>
 * <ul>
 *   <li>原則1: user_id は他ドメイン参照だが FK は張らない。</li>
 *   <li>原則6: 新規テーブルのため UUIDv7 を採用。</li>
 * </ul>
 */
@Entity
@Table(name = "village_newsletter_opt_outs")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageNewsletterOptOutEntity extends UuidV7Entity {

    /** FK → villages.id（同一ドメイン CASCADE）。 */
    @Column(name = "village_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID villageId;

    /** opt-out したユーザー ID（FK なし）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "opted_out_at", nullable = false)
    private LocalDateTime optedOutAt;

    @PrePersist
    protected void onCreate() {
        if (this.optedOutAt == null) {
            this.optedOutAt = LocalDateTime.now();
        }
    }
}
