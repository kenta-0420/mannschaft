package com.mannschaft.app.team.repository;

import com.mannschaft.app.team.entity.TeamNotificationOutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * {@code team_notification_outbox} の Repository（docs/architecture/notification_outbox.md §3・§4）。
 *
 * <p>【試練の骨格・出陣で実装】冪等 INSERT（{@code ON DUPLICATE KEY UPDATE id = id}）、claim
 * （{@code FOR UPDATE SKIP LOCKED}）、{@code claim_token} 一致の条件付き印付け、回収、掃除、最古の PENDING の
 * 問い合わせを native SQL で足す。organization_id で絞る問い合わせは持たない（原則7 の対象外。設計書 §3.4）。</p>
 */
public interface TeamNotificationOutboxRepository extends JpaRepository<TeamNotificationOutboxEntity, UUID> {
}
