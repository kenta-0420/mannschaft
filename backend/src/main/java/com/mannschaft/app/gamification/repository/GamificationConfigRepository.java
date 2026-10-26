package com.mannschaft.app.gamification.repository;

import com.mannschaft.app.gamification.entity.GamificationConfigEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * ゲーミフィケーション設定リポジトリ。
 */
public interface GamificationConfigRepository extends JpaRepository<GamificationConfigEntity, Long> {

    /**
     * スコープで設定を検索する。
     */
    Optional<GamificationConfigEntity> findByScopeTypeAndScopeId(String scopeType, Long scopeId);

    /**
     * ポイントリセット月が一致する設定をチャンクで取得する。
     * GamificationResetBatchService で使用。
     *
     * @param pointResetMonth ポイントリセット月（1〜12）
     * @param pageable        ページリクエスト
     * @return ページ結果
     */
    Page<GamificationConfigEntity> findByPointResetMonth(Byte pointResetMonth, Pageable pageable);

    /**
     * 有効かつランキング有効な設定をチャンクで取得する。
     * GamificationRankingBatchService で使用。
     *
     * @param pageable ページリクエスト
     * @return ページ結果
     */
    Page<GamificationConfigEntity> findByIsEnabledTrueAndIsRankingEnabledTrue(Pageable pageable);

    /**
     * 有効な設定をチャンクで取得する。
     * GamificationBadgeBatchService で使用。
     *
     * @param pageable ページリクエスト
     * @return ページ結果
     */
    Page<GamificationConfigEntity> findByIsEnabledTrue(Pageable pageable);
}
