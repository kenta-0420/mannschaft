package com.mannschaft.app.pointcard.repository;

import com.mannschaft.app.pointcard.entity.PointCardProviderSynonymEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ポイントカードプロバイダー同義語辞書 Repository。
 *
 * <p>{@link com.mannschaft.app.pointcard.service.ProviderMatchService} が
 * 起動時にキャッシュ構築する用途と、運営マスタ管理 UI（第二陣以降）の CRUD 用途を兼ねる。
 *
 * <p>本テーブルは運営マスタ（全テナント共有）であり {@code organization_id} を
 * 持たないため、{@code AbstractTenantAwareRepository} は適用しない。
 */
public interface PointCardProviderSynonymRepository
        extends JpaRepository<PointCardProviderSynonymEntity, UUID> {

    /**
     * 正規化キーから同義語エントリを 1 件取得する（UNIQUE 制約あり）。
     */
    Optional<PointCardProviderSynonymEntity> findBySynonymNormalized(String synonymNormalized);

    /**
     * 特定プロバイダーに紐付く同義語一覧を取得する。
     */
    List<PointCardProviderSynonymEntity> findByProviderId(UUID providerId);

    /**
     * 全件をプロバイダー ID 昇順 + 正規化キー昇順で取得する。
     * 運営マスタ管理 UI で一覧表示するときに利用する。
     */
    List<PointCardProviderSynonymEntity> findAllByOrderByProviderIdAscSynonymNormalizedAsc();

    /**
     * 特定プロバイダーに紐付く同義語の件数を取得する。
     */
    long countByProviderId(UUID providerId);
}
