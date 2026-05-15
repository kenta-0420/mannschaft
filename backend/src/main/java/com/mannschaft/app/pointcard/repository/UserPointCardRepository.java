package com.mannschaft.app.pointcard.repository;

import com.mannschaft.app.common.repository.AbstractUserOwnedRepository;
import com.mannschaft.app.pointcard.entity.UserPointCardEntity;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * ユーザー保有ポイントカードのリポジトリ。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §5.2
 *
 * <p>個人スコープのため {@link AbstractUserOwnedRepository} を継承する。
 * 一覧取得は「お気に入り優先 → display_order 昇順 → created_at 降順」の
 * 設計書 §6.4 に従い専用メソッドを定義する。
 */
@Repository
public interface UserPointCardRepository
        extends AbstractUserOwnedRepository<UserPointCardEntity, UUID> {

    /**
     * 自分のカード一覧をお気に入り優先で取得する。
     *
     * <p>並び順: {@code is_favorite DESC, display_order ASC, created_at DESC}。
     * インデックス {@code idx_upc_user_favorite (user_id, is_favorite, display_order)} を活用する。
     */
    List<UserPointCardEntity> findByUserIdOrderByFavoriteDescDisplayOrderAscCreatedAtDesc(
            Long userId);

    /**
     * 指定プロバイダーを参照しているカードの存在チェック。
     *
     * <p>プロバイダー無効化・削除時の影響範囲調査に使う運用補助メソッド。
     * DDL の {@code ON DELETE SET NULL} で整合性は保たれるが、
     * 管理画面で「○件のカードがこのプロバイダーを使用中」と表示する用途を想定。
     */
    boolean existsByProviderId(UUID providerId);
}
