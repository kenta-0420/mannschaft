package com.mannschaft.app.matching.repository;

import com.mannschaft.app.matching.entity.RegionTranslationEntity;
import com.mannschaft.app.matching.entity.RegionTranslationId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * 地域名多言語訳リポジトリ（F22.1 Phase2 E）。
 *
 * <p>マスタ例外・自然キー（{@code code} × {@code lang}）。{@code findByCodeInAndLang} で
 * 1 言語ぶんの訳をコード集合でバルク取得し、N+1 を避ける。</p>
 */
public interface RegionTranslationRepository
        extends JpaRepository<RegionTranslationEntity, RegionTranslationId> {

    /**
     * 指定言語の訳を、地域コード集合でまとめて取得する。
     *
     * @param codes 地域コード（都道府県2桁 / 市区町村5桁）の集合
     * @param lang  言語コード（en/zh/ko/es/de）
     * @return 訳が存在する分のみ（未訳コードは含まれない）
     */
    List<RegionTranslationEntity> findByCodeInAndLang(Collection<String> codes, String lang);
}
