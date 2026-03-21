package com.mannschaft.app.event.repository;

import com.mannschaft.app.event.EventScopeType;
import com.mannschaft.app.event.EventStatus;
import com.mannschaft.app.event.entity.EventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * イベントリポジトリ。
 */
public interface EventRepository extends JpaRepository<EventEntity, Long> {

    /**
     * スコープ別イベント一覧をページング取得する。
     */
    Page<EventEntity> findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
            EventScopeType scopeType, Long scopeId, Pageable pageable);

    /**
     * スコープ別・ステータス指定でイベント一覧をページング取得する。
     */
    Page<EventEntity> findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
            EventScopeType scopeType, Long scopeId, EventStatus status, Pageable pageable);

    /**
     * 公開イベント一覧をページング取得する。
     */
    Page<EventEntity> findByIsPublicTrueAndStatusOrderByCreatedAtDesc(
            EventStatus status, Pageable pageable);

    /**
     * スラグでイベントを取得する。
     */
    Optional<EventEntity> findBySlug(String slug);

    /**
     * スラグの存在を確認する。
     */
    boolean existsBySlug(String slug);

    /**
     * スコープ別のイベント件数を取得する。
     */
    long countByScopeTypeAndScopeIdAndStatus(EventScopeType scopeType, Long scopeId, EventStatus status);
}
