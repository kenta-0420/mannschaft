package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.market.MarketErrorCode;
import com.mannschaft.app.matching.entity.CityEntity;
import com.mannschaft.app.matching.repository.CityRepository;
import com.mannschaft.app.matching.repository.PrefectureRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * F22.1 市: 札の地域コード（都道府県・市区町村）整合検証（01_data_model §2 / 02_api_design §4）。
 *
 * <p>{@code recruitment} ドメインから共通マスタ {@code prefectures}/{@code cities} への参照整合は
 * クロスドメイン FK を張らず本クラス（Service 層）で検証する（CLAUDE.md 原則 1）。</p>
 *
 * <h2>検証順序（01_data_model §2）</h2>
 * <ol>
 *   <li>{@code cityCode} 指定時、{@code cities} に存在することを検証（不在は {@code MARKET_001}）。</li>
 *   <li>{@code prefectureCode} 未指定なら {@code SUBSTRING(cityCode,1,2)} で自動補完。</li>
 *   <li>{@code prefectureCode} 指定済みなら上位 2 桁との一致を検証（不一致は {@code MARKET_001}）。</li>
 *   <li>{@code prefectureCode} のみ指定なら {@code prefectures} 存在を検証（不在は {@code MARKET_001}）。</li>
 * </ol>
 *
 * <p>API レスポンスでは常に {@code prefectureCode} が埋まった状態を返すため、解決済みの値を
 * {@link ResolvedRegion} として返す。</p>
 */
@Component
@RequiredArgsConstructor
public class MarketRegionValidator {

    private final PrefectureRepository prefectureRepository;
    private final CityRepository cityRepository;

    /**
     * 地域コードを検証・正規化する。
     *
     * @param prefectureCode 都道府県コード（任意）
     * @param cityCode       市区町村コード（任意）
     * @return 正規化済みの地域コード（両方 null も可＝地域を問わない札）
     * @throws BusinessException {@code MARKET_001}（マスタ不在 / 不整合）
     */
    public ResolvedRegion validateAndNormalize(String prefectureCode, String cityCode) {
        String normalizedPref = blankToNull(prefectureCode);
        String normalizedCity = blankToNull(cityCode);

        if (normalizedCity != null) {
            CityEntity city = cityRepository.findById(normalizedCity)
                    .orElseThrow(() -> new BusinessException(MarketErrorCode.REGION_INVALID));
            String derivedPref = normalizedCity.substring(0, 2);
            if (normalizedPref == null) {
                // 自動補完
                normalizedPref = derivedPref;
            } else if (!normalizedPref.equals(derivedPref)
                    || !normalizedPref.equals(city.getPrefectureCode())) {
                // 上位 2 桁不一致 / マスタの prefecture_code 不一致
                throw new BusinessException(MarketErrorCode.REGION_INVALID);
            }
            return new ResolvedRegion(normalizedPref, normalizedCity);
        }

        if (normalizedPref != null) {
            if (!prefectureRepository.existsById(normalizedPref)) {
                throw new BusinessException(MarketErrorCode.REGION_INVALID);
            }
            return new ResolvedRegion(normalizedPref, null);
        }

        // 両方未指定: 地域を問わない札
        return new ResolvedRegion(null, null);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * 正規化済みの地域コード。
     *
     * @param prefectureCode 補完済み都道府県コード（null=地域なし）
     * @param cityCode       市区町村コード（null=県単位 or 地域なし）
     */
    public record ResolvedRegion(String prefectureCode, String cityCode) {
    }
}
