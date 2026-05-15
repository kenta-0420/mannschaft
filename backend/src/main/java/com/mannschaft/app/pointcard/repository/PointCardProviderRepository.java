package com.mannschaft.app.pointcard.repository;

import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ポイントカードプロバイダー（運営マスタ）リポジトリ。
 *
 * <p>運営マスタはテナントを跨いで共有される（CLAUDE.md 原則 6 マスタ例外）。
 * 個人スコープでも組織スコープでもないため、AbstractUserOwnedRepository /
 * AbstractTenantAwareRepository は継承せず JpaRepository を直接継承する。
 */
@Repository
public interface PointCardProviderRepository extends JpaRepository<PointCardProviderEntity, UUID> {

    /**
     * is_active=true のプロバイダーをカテゴリ昇順・表示名昇順で全件取得する。
     * プリセット一覧 API ({@code GET /providers}) で使用する。
     */
    List<PointCardProviderEntity> findAllByActiveTrueOrderByCategoryAscDisplayNameAsc();

    /**
     * 一意コードでプロバイダーを取得する。
     * 起動時キャッシュ構築・テスト用途に利用。
     */
    Optional<PointCardProviderEntity> findByCode(String code);

    /**
     * 指定組織が発行した有効なプロバイダーを全件取得する。
     * F18 Phase 2: 組織削除イベントで一括 deactivate するために利用。
     */
    List<PointCardProviderEntity> findAllByOrganizationIdAndActiveTrue(Long organizationId);
}
