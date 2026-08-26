package com.mannschaft.app.matching.service;

import com.mannschaft.app.matching.entity.RegionTranslationEntity;
import com.mannschaft.app.matching.repository.RegionTranslationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link RegionTranslationService} の回帰テスト（F22.1 Phase2 E 第二陣 zh/ko 投入の検分用）。
 *
 * <p>市区町村訳 seed（V71.009 en / V71.012 zh / V71.013 ko）の投入後も、Service の解決契約が
 * 壊れていないことを DB 非依存（Mockito）で担保する。観点は以下の 3 点:</p>
 * <ol>
 *   <li>訳がある市区町村コード（横浜市 14100）が en/zh/ko で各訳名を返す。</li>
 *   <li>第一陣/第二陣が省略したコード（日本語 fallback 対象。例: 大蔵村 06365 の ko）は
 *       訳 Map に含まれず、呼び出し側の日本語マスタ名フォールバックへ委ねられる。</li>
 *   <li>都道府県(2桁)の既存47訳の解決経路が壊れていない（東京都 13 / 大阪府 27 の回帰）。</li>
 * </ol>
 *
 * <p>Service は訳テーブルのみを参照し、未訳コードは Map に含めない契約（{@code resolveNames}）。
 * 実 seed の中身（簡体字/ハングルの正しさ）は SQL 生成時の機械検証と目視で担保し、本テストは
 * 「Service が訳をそのまま返し、未訳は欠落させる」契約の回帰固定を行う。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RegionTranslationService 回帰テスト（市区町村訳 seed 投入後）")
class RegionTranslationServiceTest {

    @Mock
    private RegionTranslationRepository regionTranslationRepository;

    @InjectMocks
    private RegionTranslationService service;

    /** {@code @AllArgsConstructor(access = PRIVATE)} の Entity をテスト用に生成する。 */
    private static RegionTranslationEntity entity(String code, String lang, String name) {
        try {
            Constructor<RegionTranslationEntity> ctor =
                    RegionTranslationEntity.class.getDeclaredConstructor(String.class, String.class, String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(code, lang, name);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("テスト用 Entity 生成に失敗", e);
        }
    }

    // ── 観点1: 訳がある市区町村コードが各言語の訳名を返す ──────────────────

    @Test
    @DisplayName("観点1: 横浜市(14100) が en/zh/ko で訳名を返す")
    void resolvesTranslatedCityNamePerLang() {
        given(regionTranslationRepository.findByCodeInAndLang(anyCodes(), eq("en")))
                .willReturn(List.of(entity("14100", "en", "Yokohama")));
        given(regionTranslationRepository.findByCodeInAndLang(anyCodes(), eq("zh")))
                .willReturn(List.of(entity("14100", "zh", "横滨市")));
        given(regionTranslationRepository.findByCodeInAndLang(anyCodes(), eq("ko")))
                .willReturn(List.of(entity("14100", "ko", "요코하마시")));

        assertThat(service.resolveNames(Set.of("14100"), "en")).containsEntry("14100", "Yokohama");
        assertThat(service.resolveNames(Set.of("14100"), "zh")).containsEntry("14100", "横滨市");
        assertThat(service.resolveNames(Set.of("14100"), "ko")).containsEntry("14100", "요코하마시");
    }

    // ── 観点2: 省略コードは訳 Map に含まれず日本語 fallback へ委ねられる ──────

    @Test
    @DisplayName("観点2: ko で省略した小規模村(大蔵村 06365)は訳 Map に含まれない（=日本語fallback）")
    void omittedCodeFallsBackToJapanese() {
        // 06365(大蔵村) は en/zh では投入したが ko では省略済み。
        // ko 取得では訳が存在しない（リポジトリは空を返す）→ Map に欠落することを固定する。
        given(regionTranslationRepository.findByCodeInAndLang(anyCodes(), eq("ko")))
                .willReturn(List.of());

        Map<String, String> resolved = service.resolveNames(Set.of("06365"), "ko");

        assertThat(resolved).doesNotContainKey("06365");
        assertThat(resolved).isEmpty();
    }

    // ── 観点3: 都道府県(2桁)の既存訳の解決経路が壊れていない（回帰） ──────────

    @Test
    @DisplayName("観点3: 都道府県(13 東京都 / 27 大阪府)の zh/ko 訳が解決される（回帰）")
    void resolvesPrefectureNamesRegression() {
        given(regionTranslationRepository.findByCodeInAndLang(anyCodes(), eq("zh")))
                .willReturn(List.of(entity("13", "zh", "东京都"), entity("27", "zh", "大阪府")));
        given(regionTranslationRepository.findByCodeInAndLang(anyCodes(), eq("ko")))
                .willReturn(List.of(entity("13", "ko", "도쿄도"), entity("27", "ko", "오사카부")));

        assertThat(service.resolveNames(Set.of("13", "27"), "zh"))
                .containsEntry("13", "东京都")
                .containsEntry("27", "大阪府");
        assertThat(service.resolveNames(Set.of("13", "27"), "ko"))
                .containsEntry("13", "도쿄도")
                .containsEntry("27", "오사카부");
    }

    // ── 補助観点: ja / 未対応言語 / 空入力は訳テーブルを引かず空 Map（既存契約の回帰） ──

    @Test
    @DisplayName("補助: ja・未対応言語・空コードは訳テーブルを引かず空 Map を返す")
    void nonTranslatableOrEmptyReturnsEmpty() {
        assertThat(service.resolveNames(Set.of("14100"), "ja")).isEmpty();
        assertThat(service.resolveNames(Set.of("14100"), "fr")).isEmpty();
        assertThat(service.resolveNames(Set.of(), "zh")).isEmpty();
        assertThat(service.resolveNames(null, "ko")).isEmpty();
    }

    @Test
    @DisplayName("補助: normalizeLang は zh/ko を正規化し ja/未対応は null（日本語fallback）")
    void normalizeLangHandlesZhKo() {
        assertThat(service.normalizeLang("ZH")).isEqualTo("zh");
        assertThat(service.normalizeLang("ko-KR")).isEqualTo("ko");
        assertThat(service.normalizeLang("ja")).isNull();
        assertThat(service.normalizeLang("th")).isNull();
        assertThat(service.isTranslatable("zh")).isTrue();
        assertThat(service.isTranslatable("ko")).isTrue();
        assertThat(service.isTranslatable("ja")).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static Collection<String> anyCodes() {
        return org.mockito.ArgumentMatchers.<Collection<String>>any();
    }
}
