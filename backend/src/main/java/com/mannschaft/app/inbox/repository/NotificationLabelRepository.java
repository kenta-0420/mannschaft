package com.mannschaft.app.inbox.repository;

import com.mannschaft.app.common.repository.AbstractUserOwnedRepository;
import com.mannschaft.app.inbox.entity.NotificationLabelEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F04.11 統合通知インボックス：軽量ラベル Repository。
 *
 * <p>{@code @SQLRestriction("deleted_at IS NULL")} により論理削除済みは全クエリから自動除外される。
 * user_id 単位の個人データのため {@code AbstractUserOwnedRepository} を継承する（IDOR 防止）。</p>
 */
public interface NotificationLabelRepository
        extends AbstractUserOwnedRepository<NotificationLabelEntity, UUID> {

    /**
     * ユーザーの現役ラベルを表示順（昇順）で取得する。
     */
    List<NotificationLabelEntity> findByUserIdOrderBySortOrderAsc(Long userId);

    /**
     * 同名ラベルが現役で存在するか（{@code @SQLRestriction} により論理削除済みは除外）。
     * 作成・改名時の現役同名重複検証に使う（設計書 02_api_design.md §3.4）。
     */
    boolean existsByUserIdAndName(Long userId, String name);

    /**
     * ユーザーの現役同名ラベルを 1 件取得する（{@code @SQLRestriction} により論理削除済みは除外）。
     * 自動ラベリング提案の 1 タップ付与（suggest-apply）で「同名があれば再利用、無ければ作成」の
     * find-or-create に使う（設計書 02_api_design.md §3.5a）。現役同名は最大 1 件（重複は作成時に禁止済み）。
     */
    Optional<NotificationLabelEntity> findByUserIdAndName(Long userId, String name);

    /**
     * 指定 ID 集合のラベルをまとめて取得する（一覧時のラベル名一括解決＝N+1 回避）。
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} により論理削除済みラベルは自動脱落する
     * （設計書 02_api_design.md §2.3 — 孤児リンクは現役ラベルのみ join される）。</p>
     */
    List<NotificationLabelEntity> findByIdIn(Collection<UUID> ids);

    /**
     * ユーザーの全ラベルを物理削除する（退会時の物理削除用）。
     *
     * <p>{@code @SQLRestriction} は SELECT のみに適用されるため、論理削除済みを含む全行を
     * 物理削除するには明示的な JPQL DELETE を用いる。</p>
     */
    @Modifying
    @Query("DELETE FROM NotificationLabelEntity e WHERE e.userId = :userId")
    void deleteAllByUserId(Long userId);
}
