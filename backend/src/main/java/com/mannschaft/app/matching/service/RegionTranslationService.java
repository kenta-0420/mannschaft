package com.mannschaft.app.matching.service;

import com.mannschaft.app.matching.entity.RegionTranslationEntity;
import com.mannschaft.app.matching.repository.RegionTranslationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 地域名の多言語解決 Service（F22.1 Phase2 E）。
 *
 * <p>地域コード→表示名の解決を担い、リクエスト言語に応じて {@code region_translations} の訳を返す。
 * 訳が無い場合・{@code ja}・未対応言語は呼び出し側が用意した日本語マスタ名（fallback）を返す。</p>
 *
 * <p>本 Service は訳テーブルのみを参照し、{@code prefectures}/{@code cities} マスタ名は引数で
 * 受け取る（地域マスタの取得責務は呼び出し側に閉じておき、ドメイン越境を増やさない）。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RegionTranslationService {

    /** 訳テーブルに格納している対応言語（ja は元マスタが正のため含めない）。 */
    private static final Set<String> SUPPORTED_LANGS = Set.of("en", "zh", "ko", "es", "de");

    private final RegionTranslationRepository regionTranslationRepository;

    /**
     * 指定言語が訳テーブルの対象か（ja / null / 未対応は false = 日本語マスタ名を使う）。
     *
     * @param lang 正規化済み言語コード（{@link #normalizeLang(String)} の結果を想定）
     * @return 訳テーブルを引くべきなら true
     */
    public boolean isTranslatable(String lang) {
        return lang != null && SUPPORTED_LANGS.contains(lang);
    }

    /**
     * リクエストの言語指定（{@code ?lang=} / {@code Accept-Language}）を内部言語コードに正規化する。
     *
     * <p>{@code "en-US"} → {@code "en"}、大文字小文字を吸収する。対応外・空・{@code ja} は
     * {@code "ja"} 扱い（= 日本語マスタ名へフォールバック）にして null を返す。</p>
     *
     * @param raw リクエストの生の言語文字列（null 可）
     * @return 対応言語なら正規化済みコード、それ以外（ja 含む）は null
     */
    public String normalizeLang(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        // "en-US,en;q=0.9" のような Accept-Language も先頭の基底言語だけ拾う。
        String base = raw.trim().toLowerCase();
        int sep = indexOfAny(base, ',', ';', '-', '_');
        if (sep >= 0) {
            base = base.substring(0, sep);
        }
        return SUPPORTED_LANGS.contains(base) ? base : null;
    }

    /**
     * 地域コード集合の訳名を 1 言語ぶんバルク取得し {@code code → 訳名} の Map を返す（N+1 回避）。
     *
     * <p>{@link #isTranslatable(String)} が false の場合や {@code codes} が空の場合は空 Map を返す。
     * 訳が無いコードは Map に含まれないため、呼び出し側で日本語名へフォールバックすること。</p>
     *
     * @param codes 地域コード集合（都道府県2桁 / 市区町村5桁）
     * @param lang  正規化済み言語コード
     * @return 訳が存在するコードのみの {@code code → 訳名}
     */
    public Map<String, String> resolveNames(Collection<String> codes, String lang) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!isTranslatable(lang) || codes == null || codes.isEmpty()) {
            return result;
        }
        for (RegionTranslationEntity t : regionTranslationRepository.findByCodeInAndLang(codes, lang)) {
            result.put(t.getCode(), t.getName());
        }
        return result;
    }

    private static int indexOfAny(String s, char... chars) {
        int min = -1;
        for (char c : chars) {
            int idx = s.indexOf(c);
            if (idx >= 0 && (min < 0 || idx < min)) {
                min = idx;
            }
        }
        return min;
    }
}
