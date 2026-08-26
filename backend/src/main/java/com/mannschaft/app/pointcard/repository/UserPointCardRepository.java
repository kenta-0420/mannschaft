package com.mannschaft.app.pointcard.repository;

import com.mannschaft.app.common.repository.AbstractUserOwnedRepository;
import com.mannschaft.app.pointcard.entity.UserPointCardEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
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

    /**
     * 再マッチバッチ対象: {@code provider_id IS NULL} のカードをページング取得する。
     *
     * <p>Phase 5 P5-S4 で導入。プロバイダーマスタ追加 / シノニム編集の影響を
     * 過去に「自由入力」として保存されたカード（fuzzy match 不成立）に遡及反映するため、
     * 夜間バッチ {@code PointCardRematchBatchService} が呼び出す。
     *
     * <p>{@link UserPointCardEntity} には論理削除カラムが無いため
     * {@code deleted_at IS NULL} 条件は不要（物理削除のみで運用、設計書 §5.2 / §10.3 参照）。
     *
     * <p>並び順は {@code id ASC}（UUIDv7 = 時系列順）で安定化する。
     */
    @Query("SELECT c FROM UserPointCardEntity c WHERE c.providerId IS NULL ORDER BY c.id ASC")
    Page<UserPointCardEntity> findRematchTargets(Pageable pageable);

    /**
     * 指定ユーザーの保有カードを全削除する（退会即時匿名化の安全弁）。
     *
     * <p>クロスドメインFK撤廃キャンペーン 第二陣C。{@code fk_upc_user}（user CASCADE）撤廃に伴い、
     * {@code PointCardAnonymizationEventListener#onUserAnonymized} が退会即時にカード（暗号化PII）を
     * 先行削除するために使用する。pointcard ドメイン内の子テーブル
     * （group_items / balance_events / stamp_events）は card_id CASCADE で自動削除される。</p>
     */
    void deleteByUserId(Long userId);
}
