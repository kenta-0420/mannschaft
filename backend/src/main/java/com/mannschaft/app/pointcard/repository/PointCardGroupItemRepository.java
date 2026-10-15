package com.mannschaft.app.pointcard.repository;

import com.mannschaft.app.pointcard.entity.PointCardGroupItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * グループ ↔ カード中間テーブルのリポジトリ。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §5.4 / §6.4
 *
 * <p>個人スコープ判定はグループ側（{@link PointCardGroupRepository}）で行うため、
 * 本リポジトリ自体は {@code user_id} を持たない。グループの所有者検証は Service 層が責任を持つ。
 */
@Repository
public interface PointCardGroupItemRepository extends JpaRepository<PointCardGroupItemEntity, UUID> {

    /** 指定グループのアイテムを表示順で取得する（軽量、カード詳細は含まない）。 */
    List<PointCardGroupItemEntity> findAllByGroupIdOrderByDisplayOrderAsc(UUID groupId);

    /** 複数グループのアイテムをまとめて取得する（GDPR エクスポート等の一括処理用）。 */
    List<PointCardGroupItemEntity> findAllByGroupIdIn(Collection<UUID> groupIds);

    /** 20 枚上限チェック用のカウント。 */
    long countByGroupId(UUID groupId);

    /** 既存重複チェック（同じカードが同じグループに 2 度入らないようにする）。 */
    boolean existsByGroupIdAndCardId(UUID groupId, UUID cardId);

    /** グループのアイテムを一括削除（updateGroup の cardIds 差し替え時）。 */
    @Modifying
    @Query("DELETE FROM PointCardGroupItemEntity i WHERE i.groupId = :groupId")
    int deleteAllByGroupId(@Param("groupId") UUID groupId);
}
