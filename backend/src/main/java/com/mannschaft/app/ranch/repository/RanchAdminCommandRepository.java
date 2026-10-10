package com.mannschaft.app.ranch.repository;

import com.mannschaft.app.ranch.entity.RanchAdminCommandEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** fresh管理者認可後、同一主体の成功ACKのみ検索する。 */
public interface RanchAdminCommandRepository extends JpaRepository<RanchAdminCommandEntity, UUID> {
    Optional<RanchAdminCommandEntity> findByActorUserIdAndIdempotencyKey(Long actorUserId, UUID idempotencyKey);
}