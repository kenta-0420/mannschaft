package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.market.MarketErrorCode;
import com.mannschaft.app.matching.entity.CityEntity;
import com.mannschaft.app.matching.repository.CityRepository;
import com.mannschaft.app.matching.repository.PrefectureRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * 複数地域（N:N）を一括で検証・正規化する（F22.1 Phase2 D）。
     *
     * <p>各要素を {@link #validateAndNormalize(String, String)} に委譲して整合検証し、
     * {@code LinkedHashSet} で<strong>正規化後の値で重複排除</strong>する（入力順は保持）。
     * 重複排除は (prefectureCode, cityCode) のペア単位で行う（県単位の重複・同一市の重複を除く）。</p>
     *
     * <p>入力が空 / null の場合は<strong>空リスト</strong>を返す（地域を問わない札を許容）。
     * 各要素は両方 null は許さない（地域指定の意図があるため）。両方 null の要素は
     * 正規化後 {@code (null,null)} となり、これは「地域なし」を表すため複数地域リストには含めない。</p>
     *
     * @param pairs 地域コードのペアリスト（{@link RegionPair}・null 可）
     * @return 正規化・重複排除済みの地域リスト（空＝地域を問わない）
     * @throws BusinessException {@code MARKET_001}（マスタ不在 / 不整合）
     */
    public List<ResolvedRegion> validateAndNormalizeAll(List<RegionPair> pairs) {
        if (pairs == null || pairs.isEmpty()) {
            return List.of();
        }
        Set<ResolvedRegion> deduped = new LinkedHashSet<>();
        for (RegionPair pair : pairs) {
            ResolvedRegion resolved = validateAndNormalize(
                    pair == null ? null : pair.prefectureCode(),
                    pair == null ? null : pair.cityCode());
            // 「地域なし（両 null）」要素は複数地域リストには含めない（空配列＝地域を問わない の意味と整合）。
            if (resolved.prefectureCode() == null && resolved.cityCode() == null) {
                continue;
            }
            deduped.add(resolved);
        }
        return new ArrayList<>(deduped);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * 検証前の地域コードペア（リクエスト入力をマスタ非依存で受ける入れ物）。
     *
     * @param prefectureCode 都道府県コード（任意）
     * @param cityCode       市区町村コード（任意）
     */
    public record RegionPair(String prefectureCode, String cityCode) {
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
