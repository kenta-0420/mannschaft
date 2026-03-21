package com.mannschaft.app.event.repository;

import com.mannschaft.app.event.entity.EventGuestInviteTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * イベントゲスト招待トークンリポジトリ。
 */
public interface EventGuestInviteTokenRepository extends JpaRepository<EventGuestInviteTokenEntity, Long> {

    /**
     * イベントの招待トークン一覧を取得する。
     */
    List<EventGuestInviteTokenEntity> findByEventIdOrderByCreatedAtDesc(Long eventId);

    /**
     * トークン文字列で招待トークンを取得する。
     */
    Optional<EventGuestInviteTokenEntity> findByToken(String token);
}
