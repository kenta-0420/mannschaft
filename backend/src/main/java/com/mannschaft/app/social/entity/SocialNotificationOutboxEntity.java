package com.mannschaft.app.social.entity;

import com.mannschaft.app.common.outbox.AbstractNotificationOutboxEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Check;

/**
 * social ドメインの通知 outbox（{@code social_notification_outbox}。docs/architecture/notification_outbox.md）。
 *
 * <p>宛先を絞った組織告知（アンケート）の push の予約を、告知の作成と同じトランザクションで1行書く
 * （F01.2.1 §8.5.3。{@code message_kind=FANOUT_WITH_AUDIENCE}）。通知ドメインの relay がコミット後に取り込み、
 * 宛先集合と fan-out ジョブを作る。UNIQUE（{@code idempotency_key}）と索引は Flyway（V239）と同じ名前で
 * {@code @Table} にも宣言する（試練の DB は Entity から生成されるため）。</p>
 *
 * <p>Repository は {@code JpaRepository} を直接継承する（organization_id で絞り込む問い合わせを持たないため、
 * {@code AbstractTenantAwareRepository} の対象外。設計書 §3.4）。</p>
 */
@Entity
@Table(name = "social_notification_outbox",
        uniqueConstraints = @UniqueConstraint(name = "uq_social_notification_outbox_idempotency_key",
                columnNames = "idempotency_key"),
        indexes = {
                @Index(name = "idx_social_notification_outbox_status_next_attempt_at",
                        columnList = "status, next_attempt_at"),
                @Index(name = "idx_social_notification_outbox_status_claimed_at", columnList = "status, claimed_at"),
                @Index(name = "idx_social_notification_outbox_status_relayed_at", columnList = "status, relayed_at"),
                @Index(name = "idx_social_notification_outbox_status_dead_at", columnList = "status, dead_at"),
                @Index(name = "idx_social_notification_outbox_organization_id", columnList = "organization_id")
        })
@Check(name = "chk_social_notification_outbox_status",
        constraints = "status IN ('PENDING','RELAYING','RELAYED','DEAD')")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class SocialNotificationOutboxEntity extends AbstractNotificationOutboxEntity {
}
