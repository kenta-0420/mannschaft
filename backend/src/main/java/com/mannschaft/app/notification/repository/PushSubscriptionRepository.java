package com.mannschaft.app.notification.repository;

import com.mannschaft.app.notification.entity.PushSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
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
     * 配信バッチ用: 複数ユーザーのプッシュ購読を一括取得する
     * （通知 fan-out 抜本改修 P1・配信 N+1 消滅）。
     *
     * <p>従来 dispatch は受信者ごとに {@code findByUserId} を発行していた（N+1）。
     * チャンク単位で本メソッドを 1 回引き、userId ごとにグルーピングしてメモリ参照する。</p>
     */
    List<PushSubscriptionEntity> findByUserIdIn(Collection<Long> userIds);

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

    /**
     * ユーザーIDに紐づくプッシュ購読をすべて削除する（退会匿名化用）。
     */
    void deleteByUserId(Long userId);
}
