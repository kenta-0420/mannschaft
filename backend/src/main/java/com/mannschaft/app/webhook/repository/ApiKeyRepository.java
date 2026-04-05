package com.mannschaft.app.webhook.repository;

import com.mannschaft.app.webhook.entity.ApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * APIキーリポジトリ。
 */
public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, Long> {

    /**
     * キープレフィックスでアクティブなAPIキー候補を取得する（bcrypt検証前の候補絞り込み用）。
     *
     * @param keyPrefix キープレフィックス（先頭8文字）
     * @return APIキーEntityリスト
     */
    List<ApiKeyEntity> findByKeyPrefixAndIsActiveTrueAndDeletedAtIsNull(String keyPrefix);

    /**
     * スコープに紐づく（削除済みを除く）APIキー数を返す。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 件数
     */
    int countByScopeTypeAndScopeIdAndDeletedAtIsNull(String scopeType, Long scopeId);

    /**
     * 指定日時以前に有効期限が切れるアクティブなAPIキーを返す（期限切れ検出バッチ用）。
     *
     * @param threshold 有効期限の閾値
     * @return APIキーEntityリスト
     */
    List<ApiKeyEntity> findByExpiresAtBeforeAndIsActiveTrueAndDeletedAtIsNull(LocalDateTime threshold);
}
