package com.mannschaft.app.organization.repository;

import com.mannschaft.app.organization.entity.OrganizationSlugHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * 組織 slug リネーム履歴リポジトリ（F01.2 §5.9.5）。
 *
 * <p>旧 slug → 新 slug の 301 解決、および旧 slug の恒久予約（再利用防止）に使う。</p>
 */
public interface OrganizationSlugHistoryRepository
        extends JpaRepository<OrganizationSlugHistoryEntity, UUID> {

    /**
     * 旧 slug 完全一致で履歴を引く（301 解決用）。
     *
     * <p>{@code old_slug} はグローバル一意（{@code uq_organization_slug_history_old_slug}）のため最大 1 件。</p>
     *
     * @param oldSlug 旧 slug
     * @return 一致した履歴（無ければ空）
     */
    Optional<OrganizationSlugHistoryEntity> findByOldSlug(String oldSlug);

    /**
     * 旧 slug が既に他組織の履歴に予約されているか（自組織を除外）。
     *
     * <p>恒久 301 リダイレクトを壊さないため、他組織の過去 slug は新規取得・リネームで弾く。
     * 自組織（{@code excludeOrganizationId}）自身の過去 slug への「戻し」は許可するため除外する。</p>
     *
     * @param oldSlug               チェック対象 slug
     * @param excludeOrganizationId 判定から除外する組織 ID（自組織）
     * @return 他組織の履歴に存在すれば true
     */
    boolean existsByOldSlugAndOrganizationIdNot(String oldSlug, Long excludeOrganizationId);

    /**
     * 旧 slug が（組織問わず）いずれかの履歴に予約されているか。
     *
     * <p>作成時の可用性チェック・新規作成検証で使う（作成時は自組織という概念が無い）。</p>
     *
     * @param oldSlug チェック対象 slug
     * @return いずれかの履歴に存在すれば true
     */
    boolean existsByOldSlug(String oldSlug);
}
