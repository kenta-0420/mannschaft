package com.mannschaft.app.team.service;

import com.mannschaft.app.matching.entity.CityEntity;
import com.mannschaft.app.matching.entity.PrefectureEntity;
import com.mannschaft.app.matching.repository.CityRepository;
import com.mannschaft.app.matching.repository.PrefectureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * F22.1 市 Phase 2 足場C 第一陣: チームの自由入力住所（都道府県名・市区町村名）を
 * マスタコード（{@code prefectures.code} / {@code cities.code}）へ逆引きする正規化器。
 *
 * <p>{@link com.mannschaft.app.recruitment.service.MarketRegionValidator} は
 * 「コード→コードの整合検証」を行うのに対し、本クラスは「名称→コードの逆引き」を行う別物である。
 * teams の既存自由入力データ（{@code prefecture} / {@code city}）を構造化コードへ寄せるバックフィルの
 * 解決ロジックを担う。</p>
 *
 * <h2>逆引き戦略</h2>
 * <h3>都道府県</h3>
 * <ol>
 *   <li>マスタ名称との完全一致（例: {@code 東京都} → {@code 13}）。</li>
 *   <li>サフィックス補完辞書: 末尾の「都/道/府/県」が欠落した入力を補完
 *       （例: {@code 東京} → {@code 東京都}、{@code 大阪} → {@code 大阪府}、{@code 神奈川} → {@code 神奈川県}）。</li>
 *   <li>別名辞書（最小）。</li>
 * </ol>
 * <h3>市区町村（都道府県確定後）</h3>
 * <ol>
 *   <li>県内で名称の完全一致。政令市区（例: {@code 札幌市中央区} = {@code 01101}）も独立行で存在するため拾える。</li>
 *   <li>失敗時、{@code ○○市○○区} 形式なら親市（{@code ○○市}）へフォールバックして完全一致。</li>
 *   <li>なお失敗時、県内で前方一致し候補が 1 件だけならそれを採用（複数候補は曖昧として不採用）。</li>
 * </ol>
 *
 * <p>最後に {@code cityCode} 上位 2 桁が解決済み {@code prefectureCode} と一致するか整合検証する。
 * いずれの段階でも解決できない場合は当該コードを {@code null} とする（例外は投げない）。</p>
 *
 * <p>表記揺れの網羅はしきれないため、実データに対するドライラン（{@code TeamRegionBackfillService}）で
 * マッチ率を測定しながら辞書を拡充する方針とする。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TeamRegionNormalizer {

    private final PrefectureRepository prefectureRepository;
    private final CityRepository cityRepository;

    /** 都道府県名サフィックス（この順で完全一致を試す）。 */
    private static final String[] PREFECTURE_SUFFIXES = {"都", "道", "府", "県"};

    /**
     * 都道府県の別名辞書（最小）。キーは入力名（正規化前）、値はマスタ正式名称。
     * <p>実データのドライランで取りこぼしが判明したら拡充する。</p>
     */
    private static final Map<String, String> PREFECTURE_ALIASES = Map.of(
            "東京都庁", "東京都"
    );

    /**
     * 自由入力の都道府県名・市区町村名を地域コードへ逆引きする。
     *
     * @param prefectureName 都道府県名（自由入力、null/空白可）
     * @param cityName       市区町村名（自由入力、null/空白可）
     * @return 解決結果。解決できなかった項目は null（例外は投げない）
     */
    public ResolvedRegion normalize(String prefectureName, String cityName) {
        String pref = blankToNull(prefectureName);
        String city = blankToNull(cityName);

        String prefectureCode = resolvePrefectureCode(pref);
        if (prefectureCode == null) {
            // 県が解決できなければ市も解決できない（コード体系上、市は県内検索が前提）。
            return new ResolvedRegion(null, null, MatchStage.NONE);
        }

        String cityCode = resolveCityCode(prefectureCode, city);
        if (cityCode == null) {
            return new ResolvedRegion(prefectureCode, null, MatchStage.PREFECTURE_ONLY);
        }

        // 整合検証: city_code 上位2桁 == prefecture_code。
        if (!cityCode.substring(0, 2).equals(prefectureCode)) {
            log.debug("[TeamRegionNormalizer] city_code 上位2桁不整合: cityCode={} prefectureCode={}",
                    cityCode, prefectureCode);
            return new ResolvedRegion(prefectureCode, null, MatchStage.PREFECTURE_ONLY);
        }

        return new ResolvedRegion(prefectureCode, cityCode, MatchStage.CITY);
    }

    /**
     * 都道府県名 → コード。完全一致 → サフィックス補完 → 別名辞書 の順で試す。
     *
     * @return 解決コード（不能時 null）
     */
    private String resolvePrefectureCode(String prefName) {
        if (prefName == null) {
            return null;
        }
        Map<String, String> nameToCode = loadPrefectureNameToCode();

        // 1. 完全一致
        String exact = nameToCode.get(prefName);
        if (exact != null) {
            return exact;
        }

        // 2. サフィックス補完（「東京」→「東京都」等）。既にサフィックスが付いていれば該当しない。
        for (String suffix : PREFECTURE_SUFFIXES) {
            String candidate = nameToCode.get(prefName + suffix);
            if (candidate != null) {
                return candidate;
            }
        }

        // 3. 別名辞書
        String aliasTarget = PREFECTURE_ALIASES.get(prefName);
        if (aliasTarget != null) {
            return nameToCode.get(aliasTarget);
        }

        return null;
    }

    /**
     * 市区町村名 → コード（都道府県確定後）。完全一致 → 親市フォールバック → 前方一致単一候補。
     *
     * @param prefectureCode 確定済み都道府県コード
     * @param cityName       市区町村名（自由入力、null 可）
     * @return 解決コード（不能時 null）
     */
    private String resolveCityCode(String prefectureCode, String cityName) {
        if (cityName == null) {
            return null;
        }

        // 1. 県内で名称完全一致（政令市区の独立行も拾える）。
        List<CityEntity> exact =
                cityRepository.findByPrefectureCodeAndNameOrderByCodeAsc(prefectureCode, cityName);
        if (exact.size() == 1) {
            return exact.get(0).getCode();
        }
        if (exact.size() > 1) {
            // 同名複数は曖昧。恣意的な基準（行政コード最小等）で誤った自治体コードを
            // バックフィルで焼き付けるのを避け、不採用とする（県のみ解決に留める）。
            // 前方一致段の「複数候補は曖昧として不採用」と方針を統一。
            // ※県内の市区町村名は JIS 上ほぼ一意のため、本分岐は異常系（マスタ重複）のみ。
            return null;
        }

        // 2. 親市フォールバック: 「○○市○○区」形式なら「○○市」で再検索。
        int wardIdx = cityName.indexOf('区');
        int cityIdx = cityName.indexOf('市');
        if (cityIdx >= 0 && wardIdx > cityIdx) {
            String parentCity = cityName.substring(0, cityIdx + 1);
            List<CityEntity> parent =
                    cityRepository.findByPrefectureCodeAndNameOrderByCodeAsc(prefectureCode, parentCity);
            if (!parent.isEmpty()) {
                return parent.get(0).getCode();
            }
        }

        // 3. 前方一致で候補が 1 件だけならそれを採用（複数候補は曖昧として不採用）。
        List<CityEntity> prefixMatches =
                cityRepository.findByPrefectureCodeAndNameStartingWithOrderByCodeAsc(prefectureCode, cityName);
        if (prefixMatches.size() == 1) {
            return prefixMatches.get(0).getCode();
        }

        return null;
    }

    /**
     * 都道府県マスタを名称→コードの Map に変換して返す。47 件と小さいため都度ロードする。
     */
    private Map<String, String> loadPrefectureNameToCode() {
        List<PrefectureEntity> all = prefectureRepository.findAllByOrderByCodeAsc();
        Map<String, String> map = new HashMap<>(all.size() * 2);
        for (PrefectureEntity p : all) {
            map.put(p.getName(), p.getCode());
        }
        return map;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * 名称→コード逆引きの解決結果。
     *
     * @param prefectureCode 解決済み都道府県コード（null=未解決）
     * @param cityCode       解決済み市区町村コード（null=県単位 or 未解決）
     * @param matchStage     どの段階まで解決できたか（集計・ログ用）
     */
    public record ResolvedRegion(String prefectureCode, String cityCode, MatchStage matchStage) {
    }

    /**
     * 逆引きがどの段階まで成功したかを表す。マッチ率集計に用いる。
     */
    public enum MatchStage {
        /** 都道府県も解決できなかった。 */
        NONE,
        /** 都道府県までは解決できたが市区町村は未解決。 */
        PREFECTURE_ONLY,
        /** 市区町村まで解決できた。 */
        CITY
    }
}
