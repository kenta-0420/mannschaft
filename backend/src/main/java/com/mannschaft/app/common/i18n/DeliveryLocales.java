package com.mannschaft.app.common.i18n;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 通知配信で使う<b>配信ロケール bucket</b>の唯一の正規化点（Issue #2871）。
 *
 * <h2>なぜ「一箇所」でなければならないか</h2>
 * <p>fan-out の受信者ソースは 4 実装（VILLAGE / TEAM / SCHEDULE_KEEP_TEAM / ORGANIZATION）あり、
 * さらに enqueue 側でも「どのロケールぶんの文面を描画するか」を決める。ここが実装ごとに
 * バラバラだと、たとえば {@code zh-CN} の利用者が受信者ソース A では {@code zh} に落ち、
 * enqueue では {@code zh-CN} のまま描画キーに使われる、という食い違いが起きて
 * <b>その利用者だけ文面が引けず配信落ちする</b>。そこで正規化を本クラスへ一元化し、
 * 全経路が {@link #normalize(String)} を通る構造にする。</p>
 *
 * <h2>base properties は配信 bucket ではない</h2>
 * <p>{@code messages.properties}（サフィックス無し）は Spring の
 * {@link org.springframework.context.MessageSource} が最後に見るフォールバック資源であり、
 * 「7 番目の配信ロケール」ではない。配信 bucket はあくまで {@link #TAGS} の 6 種で、
 * 描画は必ずこの 6 種のいずれかの {@link Locale} で行う。</p>
 */
public final class DeliveryLocales {

    /** 配信 bucket（この 6 種以外は存在しない）。 */
    public static final List<String> TAGS = List.of("ja", "en", "zh", "ko", "es", "de");

    /** 既定 bucket。null・空・未対応タグはすべてここへ落とす。 */
    public static final String DEFAULT_TAG = "ja";

    private static final Set<String> SUPPORTED = Set.of("ja", "en", "zh", "ko", "es", "de");

    /** タグ → {@link Locale}（{@code Locale.forLanguageTag} の結果を固定化して毎回組み立てない）。 */
    private static final Map<String, Locale> LOCALES = Map.of(
            "ja", Locale.JAPANESE,
            "en", Locale.ENGLISH,
            "zh", Locale.CHINESE,
            "ko", Locale.KOREAN,
            "es", Locale.of("es"),
            "de", Locale.GERMAN);

    private DeliveryLocales() {
    }

    /**
     * 利用者の locale 値（DB の {@code users.locale}）を配信 bucket 6 種のいずれかへ正規化する。
     *
     * <p>扱う入力:
     * <ul>
     *   <li>{@code null} / 空文字 / 空白のみ → {@link #DEFAULT_TAG}</li>
     *   <li>地域タグ付き（{@code zh-CN} / {@code zh_Hans} / {@code en-US}）→ 言語サブタグだけを見る</li>
     *   <li>大文字小文字の揺れ（{@code JA} / {@code Ja}）→ 小文字化して判定</li>
     *   <li>未対応言語（{@code fr} / {@code pt} 等）→ {@link #DEFAULT_TAG}</li>
     * </ul>
     * </p>
     */
    public static String normalize(String rawLocale) {
        if (rawLocale == null) {
            return DEFAULT_TAG;
        }
        String trimmed = rawLocale.trim();
        if (trimmed.isEmpty()) {
            return DEFAULT_TAG;
        }
        // 地域・スクリプトサブタグ（zh-CN / zh_Hans / en-US）は落として言語サブタグだけを見る。
        int sep = indexOfSeparator(trimmed);
        String language = (sep < 0 ? trimmed : trimmed.substring(0, sep)).toLowerCase(Locale.ROOT);
        return SUPPORTED.contains(language) ? language : DEFAULT_TAG;
    }

    /** 正規化済みタグ（{@link #normalize} の戻り値）に対応する {@link Locale} を返す。 */
    public static Locale toLocale(String normalizedTag) {
        Locale locale = LOCALES.get(normalizedTag);
        // 未正規化の値が渡された場合も落とさず既定へ寄せる（呼び出し側の順序ミスで配信を止めない）。
        return locale != null ? locale : LOCALES.get(DEFAULT_TAG);
    }

    private static int indexOfSeparator(String tag) {
        for (int i = 0; i < tag.length(); i++) {
            char c = tag.charAt(i);
            if (c == '-' || c == '_') {
                return i;
            }
        }
        return -1;
    }
}
