package com.mannschaft.app.notification.repository;

import com.mannschaft.app.notification.entity.NotificationPreferenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 通知設定リポジトリ。
 */
public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreferenceEntity, Long> {

    /**
     * 配信バッチ用: 指定スコープ（{@code scopeType} + {@code scopeId}）における複数ユーザーの
     * 通知設定を一括取得する（通知 fan-out 抜本改修 P1・配信 N+1 消滅）。
     *
     * <p>従来 dispatch は受信者ごとに {@code findByUserIdAndScopeTypeAndScopeId} を発行していた（N+1）。
     * チャンク単位で本メソッドを 1 回引き、Map 化してメモリ参照で配信可否を判定する。
     * {@code scopeId} が NULL のスコープ（SYSTEM 等）も {@code IS NULL} で正しく一致させる。</p>
     *
     * @param userIds   受信者チャンク
     * @param scopeType スコープ種別
     * @param scopeId   スコープID（NULL 可）
     * @return 該当するスコープ設定行（存在しないユーザーは既定 ON として呼び出し側で補完する）
     */
    @Query("""
            SELECT p FROM NotificationPreferenceEntity p
            WHERE p.userId IN :userIds
              AND p.scopeType = :scopeType
              AND ((:scopeId IS NULL AND p.scopeId IS NULL) OR p.scopeId = :scopeId)
            """)
    List<NotificationPreferenceEntity> findForDispatchBatch(
            @Param("userIds") Collection<Long> userIds,
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * ユーザーの通知設定一覧を取得する。
     */
    List<NotificationPreferenceEntity> findByUserId(Long userId);

    /**
     * ユーザー・スコープで通知設定を取得する。
     */
    Optional<NotificationPreferenceEntity> findByUserIdAndScopeTypeAndScopeId(
            Long userId, String scopeType, Long scopeId);

    /**
     * ユーザーIDとスコープタイプで通知設定一覧を取得する。
     */
    List<NotificationPreferenceEntity> findByUserIdAndScopeType(Long userId, String scopeType);

    /**
     * ユーザーIDに紐づく通知設定をすべて削除する（退会匿名化用）。
     */
    void deleteByUserId(Long userId);
}
