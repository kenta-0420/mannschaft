package com.mannschaft.app.notification.repository;

import com.mannschaft.app.notification.entity.PushSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * プッシュ購読リポジトリ。
 */
public interface PushSubscriptionRepository extends JpaRepository<PushSubscriptionEntity, Long> {

    /**
     * ユーザーのプッシュ購読一覧を取得する。
     */
    List<PushSubscriptionEntity> findByUserId(Long userId);

    /**
     * エンドポイントでプッシュ購読を取得する。
     */
    Optional<PushSubscriptionEntity> findByEndpoint(String endpoint);

    /**
     * エンドポイントが既に存在するか確認する。
     */
    boolean existsByEndpoint(String endpoint);

    /**
     * エンドポイントでプッシュ購読を削除する。
     */
    void deleteByEndpoint(String endpoint);

    /**
     * ユーザーのプッシュ購読件数を取得する。
     */
    long countByUserId(Long userId);
}
