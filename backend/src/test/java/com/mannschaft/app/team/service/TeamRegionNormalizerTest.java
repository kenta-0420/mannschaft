package com.mannschaft.app.team.service;

import com.mannschaft.app.matching.service.RegionMasterLookupService;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/** {@link TeamRegionNormalizer} の名称から地域コードへの正規化を検証する。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamRegionNormalizer 単体テスト")
class TeamRegionNormalizerTest {

    @Mock
    private RegionMasterLookupService regionMasterLookupService;

    @InjectMocks
    private TeamRegionNormalizer normalizer;

    @BeforeEach
    void setUpPrefectures() {
        given(regionMasterLookupService.findPrefectureCodesByName()).willReturn(Map.of(
                "北海道", "01",
                "東京都", "13",
                "神奈川県", "14",
                "大阪府", "27"));
    }

    private static RegionMasterLookupService.City city(String code, String prefectureCode, String name) {
        return new RegionMasterLookupService.City(code, prefectureCode, name);
    }

    @Test
    @DisplayName("都道府県と市区町村の完全一致はCITYを返す")
    void exactMatch_both() {
        given(regionMasterLookupService.findCitiesByPrefectureCodeAndName("01", "旭川市"))
                .willReturn(List.of(city("01202", "01", "旭川市")));

        assertThat(normalizer.normalize("北海道", "旭川市"))
                .isEqualTo(new ResolvedRegion("01", "01202", MatchStage.CITY));
    }

    @Test
    @DisplayName("都道府県接尾辞の補完は従来どおりに解決する")
    void prefectureSuffixCompletion() {
        assertThat(normalizer.normalize("東京", null))
                .isEqualTo(new ResolvedRegion("13", null, MatchStage.PREFECTURE_ONLY));
        assertThat(normalizer.normalize("大阪", null))
                .isEqualTo(new ResolvedRegion("27", null, MatchStage.PREFECTURE_ONLY));
    }

    @Test
    @DisplayName("指定都市の区は親市フォールバックで解決する")
    void parentCityFallback() {
        given(regionMasterLookupService.findCitiesByPrefectureCodeAndName("01", "札幌市北区"))
                .willReturn(List.of());
        given(regionMasterLookupService.findCitiesByPrefectureCodeAndName("01", "札幌市"))
                .willReturn(List.of(city("01100", "01", "札幌市")));

        assertThat(normalizer.normalize("北海道", "札幌市北区"))
                .isEqualTo(new ResolvedRegion("01", "01100", MatchStage.CITY));
    }

    @Test
    @DisplayName("不整合な市コードは都道府県だけを残す")
    void cityCodePrefixMismatch_dropsCityCode() {
        given(regionMasterLookupService.findCitiesByPrefectureCodeAndName("14", "架空市"))
                .willReturn(List.of(city("27999", "14", "架空市")));

        assertThat(normalizer.normalize("神奈川県", "架空市"))
                .isEqualTo(new ResolvedRegion("14", null, MatchStage.PREFECTURE_ONLY));
    }

    @Test
    @DisplayName("大阪府の接尾辞補完")
    void prefectureSuffixCompletion_osaka() {
        assertThat(normalizer.normalize("大阪", null))
                .isEqualTo(new ResolvedRegion("27", null, MatchStage.PREFECTURE_ONLY));
    }

    @Test
    @DisplayName("政令指定都市の区を完全一致で解決する")
    void designatedCityWard_exactMatch() {
        given(regionMasterLookupService.findCitiesByPrefectureCodeAndName("01", "札幌市中央区"))
                .willReturn(List.of(city("01101", "01", "札幌市中央区")));

        assertThat(normalizer.normalize("北海道", "札幌市中央区"))
                .isEqualTo(new ResolvedRegion("01", "01101", MatchStage.CITY));
    }

    @Test
    @DisplayName("単一の接頭辞一致を解決する")
    void prefixMatch_single() {
        given(regionMasterLookupService.findCitiesByPrefectureCodeAndName("13", "新宿"))
                .willReturn(List.of());
        given(regionMasterLookupService.findCitiesByPrefectureCodeAndNameStartingWith("13", "新宿"))
                .willReturn(List.of(city("13113", "13", "新宿区")));

        assertThat(normalizer.normalize("東京都", "新宿"))
                .isEqualTo(new ResolvedRegion("13", "13113", MatchStage.CITY));
    }

    @Test
    @DisplayName("完全一致が複数なら市区町村コードを採用しない")
    void exactMatch_multiple_rejected() {
        given(regionMasterLookupService.findCitiesByPrefectureCodeAndName("13", "あいまい市"))
                .willReturn(List.of(city("13201", "13", "あいまい市"), city("13202", "13", "あいまい市")));

        assertThat(normalizer.normalize("東京都", "あいまい市"))
                .isEqualTo(new ResolvedRegion("13", null, MatchStage.PREFECTURE_ONLY));
    }

    @Test
    @DisplayName("接頭辞一致が複数なら市区町村コードを採用しない")
    void prefixMatch_multiple_rejected() {
        given(regionMasterLookupService.findCitiesByPrefectureCodeAndName("13", "府中"))
                .willReturn(List.of());
        given(regionMasterLookupService.findCitiesByPrefectureCodeAndNameStartingWith("13", "府中"))
                .willReturn(List.of(city("13206", "13", "府中市"), city("13999", "13", "府中町")));

        assertThat(normalizer.normalize("東京都", "府中"))
                .isEqualTo(new ResolvedRegion("13", null, MatchStage.PREFECTURE_ONLY));
    }

    @Test
    @DisplayName("未解決の都道府県はNONEを返す")
    void prefectureUnresolved_returnsNone() {
        assertThat(normalizer.normalize("存在しない県", "どこか市"))
                .isEqualTo(new ResolvedRegion(null, null, MatchStage.NONE));
    }

    @Test
    @DisplayName("空入力はNONEを返す")
    void bothBlank_returnsNone() {
        assertThat(normalizer.normalize(" ", null))
                .isEqualTo(new ResolvedRegion(null, null, MatchStage.NONE));
    }

    @Test
    @DisplayName("市だけでは都道府県を推測しない")
    void cityOnly_noPrefecture_returnsNone() {
        assertThat(normalizer.normalize(null, "旭川市"))
                .isEqualTo(new ResolvedRegion(null, null, MatchStage.NONE));
    }

    @Test
    @DisplayName("都道府県名の完全一致を接尾辞補完より優先する")
    void exactPrefectureMatch_preferred() {
        assertThat(normalizer.normalize("東京都", null))
                .isEqualTo(new ResolvedRegion("13", null, MatchStage.PREFECTURE_ONLY));
    }
}
