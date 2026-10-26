package com.mannschaft.app.incidentbanner.repository;

import com.mannschaft.app.incidentbanner.entity.IncidentBannerTranslationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 障害告知バナー翻訳リポジトリ。
 */
public interface IncidentBannerTranslationRepository extends JpaRepository<IncidentBannerTranslationEntity, UUID> {

    /**
     * 指定バナーの全翻訳を取得する。
     *
     * @param bannerId バナーID
     * @return 翻訳一覧
     */
    List<IncidentBannerTranslationEntity> findByBannerId(UUID bannerId);

    /**
     * 指定バナー・指定言語の翻訳を取得する。
     *
     * @param bannerId バナーID
     * @param language 言語コード
     * @return 翻訳（存在する場合）
     */
    Optional<IncidentBannerTranslationEntity> findByBannerIdAndLanguage(UUID bannerId, String language);
}
