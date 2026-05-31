package com.mannschaft.app.team.service;

import com.mannschaft.app.matching.entity.CityEntity;
import com.mannschaft.app.matching.entity.PrefectureEntity;
import com.mannschaft.app.matching.repository.CityRepository;
import com.mannschaft.app.matching.repository.PrefectureRepository;
import com.mannschaft.app.team.service.TeamRegionNormalizer.MatchStage;
import com.mannschaft.app.team.service.TeamRegionNormalizer.ResolvedRegion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

/**
 * {@link TeamRegionNormalizer} の単体テスト（名称→コード逆引き）。
 *
 * <p>City/Prefecture Repository を Mockito でスタブする DB 不要の純 UT。
 * MarketRegionValidator（コード→コード検証）とは別物であることに留意。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamRegionNormalizer 単体テスト（名称→コード逆引き）")
class TeamRegionNormalizerTest {

    @Mock
    private PrefectureRepository prefectureRepository;

    @Mock
    private CityRepository cityRepository;

    @InjectMocks
    private TeamRegionNormalizer normalizer;

    private static CityEntity city(String code, String prefCode, String name) {
        try {
            var ctor = CityEntity.class.getDeclaredConstructor(String.class, String.class, String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(code, prefCode, name);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static PrefectureEntity prefecture(String code, String name) {
        try {
            var ctor = PrefectureEntity.class.getDeclaredConstructor(String.class, String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(code, name);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUpPrefectures() {
        // 全テストで都道府県マスタは共通（lenient: 都道府県のみ検証するテストもある）。
        lenient().when(prefectureRepository.findAllByOrderByCodeAsc()).thenReturn(List.of(
                prefecture("01", "北海道"),
                prefecture("13", "東京都"),
                prefecture("14", "神奈川県"),
                prefecture("27", "大阪府")
        ));
    }

    @Test
    @DisplayName("都道府県・市区町村ともに完全一致 → 両コード解決（CITY）")
    void exactMatch_both() {
        given(cityRepository.findByPrefectureCodeAndNameOrderByCodeAsc("01", "函館市"))
                .willReturn(List.of(city("01202", "01", "函館市")));

        ResolvedRegion r = normalizer.normalize("北海道", "函館市");

        assertThat(r.prefectureCode()).isEqualTo("01");
        assertThat(r.cityCode()).isEqualTo("01202");
        assertThat(r.matchStage()).isEqualTo(MatchStage.CITY);
    }

    @Test
    @DisplayName("都道府県サフィックス補完: 『東京』→『東京都』(13)、市は未指定で PREFECTURE_ONLY")
    void prefectureSuffixCompletion() {
        ResolvedRegion r = normalizer.normalize("東京", null);

        assertThat(r.prefectureCode()).isEqualTo("13");
        assertThat(r.cityCode()).isNull();
        assertThat(r.matchStage()).isEqualTo(MatchStage.PREFECTURE_ONLY);
    }

    @Test
    @DisplayName("都道府県サフィックス補完: 『大阪』→『大阪府』(27)")
    void prefectureSuffixCompletion_osaka() {
        ResolvedRegion r = normalizer.normalize("大阪", null);

        assertThat(r.prefectureCode()).isEqualTo("27");
        assertThat(r.matchStage()).isEqualTo(MatchStage.PREFECTURE_ONLY);
    }

    @Test
    @DisplayName("政令市区: 『札幌市中央区』が独立行として完全一致 → 01101")
    void designatedCityWard_exactMatch() {
        given(cityRepository.findByPrefectureCodeAndNameOrderByCodeAsc("01", "札幌市中央区"))
                .willReturn(List.of(city("01101", "01", "札幌市中央区")));

        ResolvedRegion r = normalizer.normalize("北海道", "札幌市中央区");

        assertThat(r.prefectureCode()).isEqualTo("01");
        assertThat(r.cityCode()).isEqualTo("01101");
        assertThat(r.matchStage()).isEqualTo(MatchStage.CITY);
    }

    @Test
    @DisplayName("親市フォールバック: 『○○市××区』が独立行に無い → 親市『○○市』で解決")
    void parentCityFallback() {
        // 完全一致は空（区が独立行として登録されていない）。
        given(cityRepository.findByPrefectureCodeAndNameOrderByCodeAsc("01", "札幌市幻区"))
                .willReturn(List.of());
        // 親市は存在する。
        given(cityRepository.findByPrefectureCodeAndNameOrderByCodeAsc("01", "札幌市"))
                .willReturn(List.of(city("01100", "01", "札幌市")));

        ResolvedRegion r = normalizer.normalize("北海道", "札幌市幻区");

        assertThat(r.cityCode()).isEqualTo("01100");
        assertThat(r.matchStage()).isEqualTo(MatchStage.CITY);
    }

    @Test
    @DisplayName("前方一致で候補が単一 → 採用")
    void prefixMatch_single() {
        given(cityRepository.findByPrefectureCodeAndNameOrderByCodeAsc("13", "渋谷"))
                .willReturn(List.of());
        given(cityRepository.findByPrefectureCodeAndNameStartingWithOrderByCodeAsc("13", "渋谷"))
                .willReturn(List.of(city("13113", "13", "渋谷区")));

        ResolvedRegion r = normalizer.normalize("東京都", "渋谷");

        assertThat(r.cityCode()).isEqualTo("13113");
        assertThat(r.matchStage()).isEqualTo(MatchStage.CITY);
    }

    @Test
    @DisplayName("名称完全一致で複数候補 → 曖昧として不採用（PREFECTURE_ONLY・誤コード焼き付け防止）")
    void exactMatch_multiple_rejected() {
        // 県内同名複数（JIS上ほぼ無いが異常系=マスタ重複）。恣意的基準で誤コードを採用しない。
        given(cityRepository.findByPrefectureCodeAndNameOrderByCodeAsc("13", "あいまい市"))
                .willReturn(List.of(
                        city("13201", "13", "あいまい市"),
                        city("13202", "13", "あいまい市")));

        ResolvedRegion r = normalizer.normalize("東京都", "あいまい市");

        assertThat(r.prefectureCode()).isEqualTo("13");
        assertThat(r.cityCode()).isNull();
        assertThat(r.matchStage()).isEqualTo(MatchStage.PREFECTURE_ONLY);
    }

    @Test
    @DisplayName("前方一致で複数候補 → 曖昧として不採用（PREFECTURE_ONLY）")
    void prefixMatch_multiple_rejected() {
        given(cityRepository.findByPrefectureCodeAndNameOrderByCodeAsc("13", "府中"))
                .willReturn(List.of());
        given(cityRepository.findByPrefectureCodeAndNameStartingWithOrderByCodeAsc("13", "府中"))
                .willReturn(List.of(
                        city("13206", "13", "府中市"),
                        city("13999", "13", "府中町")));

        ResolvedRegion r = normalizer.normalize("東京都", "府中");

        assertThat(r.prefectureCode()).isEqualTo("13");
        assertThat(r.cityCode()).isNull();
        assertThat(r.matchStage()).isEqualTo(MatchStage.PREFECTURE_ONLY);
    }

    @Test
    @DisplayName("都道府県が解決不能 → 両コード NULL（NONE）。市は検索しない")
    void prefectureUnresolved_returnsNone() {
        ResolvedRegion r = normalizer.normalize("存在しない県", "どこか市");

        assertThat(r.prefectureCode()).isNull();
        assertThat(r.cityCode()).isNull();
        assertThat(r.matchStage()).isEqualTo(MatchStage.NONE);
    }

    @Test
    @DisplayName("両方 null/空白 → NONE（例外を投げない）")
    void bothBlank_returnsNone() {
        ResolvedRegion r = normalizer.normalize("  ", null);

        assertThat(r.prefectureCode()).isNull();
        assertThat(r.cityCode()).isNull();
        assertThat(r.matchStage()).isEqualTo(MatchStage.NONE);
    }

    @Test
    @DisplayName("整合検証: 解決した city_code 上位2桁が prefecture_code と不一致なら市を捨て PREFECTURE_ONLY")
    void cityCodePrefixMismatch_dropsCityCode() {
        // マスタ不整合（神奈川県(14)なのに大阪(27)始まりのコードが返る異常系）を模擬。
        given(cityRepository.findByPrefectureCodeAndNameOrderByCodeAsc("14", "怪市"))
                .willReturn(List.of(city("27999", "14", "怪市")));

        ResolvedRegion r = normalizer.normalize("神奈川県", "怪市");

        assertThat(r.prefectureCode()).isEqualTo("14");
        assertThat(r.cityCode()).isNull();
        assertThat(r.matchStage()).isEqualTo(MatchStage.PREFECTURE_ONLY);
    }

    @Test
    @DisplayName("市名のみで都道府県が空 → 県解決不能で NONE（市は県確定が前提）")
    void cityOnly_noPrefecture_returnsNone() {
        ResolvedRegion r = normalizer.normalize(null, "函館市");

        assertThat(r.matchStage()).isEqualTo(MatchStage.NONE);
        // 県確定前は市検索を行わないことを保証（不要なリポジトリ呼び出しがない）。
        assertThat(r.cityCode()).isNull();
    }

    @Test
    @DisplayName("既にサフィックス付き完全一致が優先される（『東京都』→ 13）")
    void exactPrefectureMatch_preferred() {
        // anyString スタブで誤補完が起きないことの保険。
        lenient().when(cityRepository.findByPrefectureCodeAndNameOrderByCodeAsc(anyString(), anyString()))
                .thenReturn(List.of());

        ResolvedRegion r = normalizer.normalize("東京都", null);

        assertThat(r.prefectureCode()).isEqualTo("13");
        assertThat(r.matchStage()).isEqualTo(MatchStage.PREFECTURE_ONLY);
    }
}
