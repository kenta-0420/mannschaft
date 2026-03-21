package com.mannschaft.app.event.repository;

import com.mannschaft.app.event.RegistrationStatus;
import com.mannschaft.app.event.entity.EventRegistrationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * イベント参加登録リポジトリ。
 */
public interface EventRegistrationRepository extends JpaRepository<EventRegistrationEntity, Long> {

    /**
     * イベントの参加登録一覧をページング取得する。
     */
    Page<EventRegistrationEntity> findByEventIdOrderByCreatedAtDesc(Long eventId, Pageable pageable);

    /**
     * イベントの参加登録をステータス指定でページング取得する。
     */
    Page<EventRegistrationEntity> findByEventIdAndStatusOrderByCreatedAtDesc(
            Long eventId, RegistrationStatus status, Pageable pageable);

    /**
     * ユーザーの特定イベントへの登録を取得する。
     */
    Optional<EventRegistrationEntity> findByEventIdAndUserId(Long eventId, Long userId);

    /**
     * ユーザーの特定イベントへの登録が存在するか確認する。
     */
    boolean existsByEventIdAndUserId(Long eventId, Long userId);

    /**
     * ゲストメールの特定イベントへの登録を取得する。
     */
    Optional<EventRegistrationEntity> findByEventIdAndGuestEmail(Long eventId, String guestEmail);

    /**
     * イベントのステータス別参加登録数を取得する。
     */
    long countByEventIdAndStatus(Long eventId, RegistrationStatus status);
}
