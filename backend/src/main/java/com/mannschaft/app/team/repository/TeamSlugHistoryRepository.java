package com.mannschaft.app.team.repository;

import com.mannschaft.app.team.entity.TeamSlugHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * チーム slug リネーム履歴リポジトリ（F01.2 §5.9.5）。
 *
 * <p>旧 slug → 新 slug の 301 解決、および旧 slug の恒久予約（再利用防止）に使う。</p>
 */
public interface TeamSlugHistoryRepository extends JpaRepository<TeamSlugHistoryEntity, java.util.UUID> {

    /**
     * 旧 slug 完全一致で履歴を引く（301 解決用）。
     *
     * <p>{@code old_slug} はグローバル一意（{@code uq_team_slug_history_old_slug}）のため最大 1 件。</p>
     *
     * @param oldSlug 旧 slug
     * @return 一致した履歴（無ければ空）
     */
    Optional<TeamSlugHistoryEntity> findByOldSlug(String oldSlug);

    /**
     * 旧 slug が既に他チームの履歴に予約されているか（自チームを除外）。
     *
     * <p>恒久 301 リダイレクトを壊さないため、他チームの過去 slug は新規取得・リネームで弾く。
     * 自チーム（{@code excludeTeamId}）自身の過去 slug への「戻し」は許可するため除外する。</p>
     *
     * @param oldSlug       チェック対象 slug
     * @param excludeTeamId 判定から除外するチーム ID（自チーム）
     * @return 他チームの履歴に存在すれば true
     */
    boolean existsByOldSlugAndTeamIdNot(String oldSlug, Long excludeTeamId);

    /**
     * 旧 slug が（チーム問わず）いずれかの履歴に予約されているか。
     *
     * <p>作成時の可用性チェック・新規作成検証で使う（作成時は自チームという概念が無い）。</p>
     *
     * @param oldSlug チェック対象 slug
     * @return いずれかの履歴に存在すれば true
     */
    boolean existsByOldSlug(String oldSlug);
}
