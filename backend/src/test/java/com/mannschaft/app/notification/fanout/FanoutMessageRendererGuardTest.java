package com.mannschaft.app.notification.fanout;

import com.mannschaft.app.common.i18n.DeliveryLocales;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #2871 の番人（AC-1 / AC-5 / AC-6 / AC-7 / AC-8）。
 *
 * <p>Spring context を立てず、本番と同じ構成（{@code useCodeAsDefaultMessage(false)} ＋
 * {@code fallbackToSystemLocale(false)}）の {@link MessageSource} を直接組み立てて検証する。
 * context を経由しないぶん高速で、かつ「本番の MessageSource 設定に依存した挙動」
 * （欠落キーで例外を投げること）をそのまま確かめられる。</p>
 */
@DisplayName("fan-out 文面レンダラ番人（Issue #2871）")
class FanoutMessageRendererGuardTest {

    /** 日本語（ひらがな・カタカナ・漢字）の検出。AC-7 の判定軸。 */
    private static final Pattern JAPANESE = Pattern.compile("[\\u3041-\\u3096\\u30A1-\\u30FA\\u4E00-\\u9FA5]");

    /** 本番 {@code I18nConfig#messageSource} と同一設定の MessageSource。 */
    private static MessageSource productionLikeMessageSource() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasenames("classpath:messages", "classpath:ValidationMessages", "classpath:email/email");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(false);
        return source;
    }

    private static FanoutMessageRenderer newRenderer() {
        return new FanoutMessageRenderer(productionLikeMessageSource(), null);
    }

    // =================================================================
    // AC-1 / AC-7: 枠は翻訳され、利用者が書いた中身はそのまま通る
    // =================================================================

    @Nested
    @DisplayName("AC-1 / AC-7: 枠だけ翻訳し、利用者コンテンツは素通しする")
    class FrameAndUserContent {

        @Test
        @DisplayName("AC-1: 全 7 種の文面種別が 6 配信ロケールぶん描画でき、利用者が書いた名称がそのまま含まれる")
        void 全種別_全ロケールで描画でき利用者コンテンツが素通しされる() {
            FanoutMessageRenderer renderer = newRenderer();
            // 利用者が書いた「翻訳してはならない」文字列（日本語・記号・絵文字を混ぜる）。
            String userContent = "第3回 夏祭り🎆（雨天決行）";

            for (FanoutMessageKind kind : FanoutMessageKind.values()) {
                Map<String, FanoutMessageRenderer.RenderedMessage> rendered =
                        renderer.renderAllLocales(kind, userContent);

                assertThat(rendered.keySet())
                        .as("%s: 配信 bucket 6 種すべてが揃う", kind)
                        .containsExactlyInAnyOrderElementsOf(DeliveryLocales.TAGS);

                if (kind.argCount() == 0) {
                    continue; // 可変部分が無い種別（SHIFT_PUBLISHED）は素通し検査の対象外。
                }
                for (String tag : DeliveryLocales.TAGS) {
                    FanoutMessageRenderer.RenderedMessage message = rendered.get(tag);
                    assertThat(message.title() + "\n" + message.body())
                            .as("%s/%s: 利用者が書いた名称は翻訳も改変もされずそのまま含まれる", kind, tag)
                            .contains(userContent);
                }
            }
        }

        @Test
        @DisplayName("AC-7: en の枠に日本語文字が含まれない（利用者コンテンツを除いた部分だけを見る）")
        void en_の枠には日本語が無い() {
            FanoutMessageRenderer renderer = newRenderer();
            // 利用者コンテンツは意図的に日本語にする。これを除いた「枠」だけを判定するため、
            // 描画結果から利用者コンテンツを取り除いてから日本語検出をかける（偽陽性の排除）。
            String userContent = "夏祭り";

            for (FanoutMessageKind kind : FanoutMessageKind.values()) {
                FanoutMessageRenderer.RenderedMessage message =
                        renderer.renderAllLocales(kind, userContent).get("en");
                String titleFrame = message.title().replace(userContent, "");
                String bodyFrame = message.body() == null ? "" : message.body().replace(userContent, "");

                assertThat(JAPANESE.matcher(titleFrame).find())
                        .as("%s: en のタイトル枠に日本語が残っている: %s", kind, message.title())
                        .isFalse();
                assertThat(JAPANESE.matcher(bodyFrame).find())
                        .as("%s: en の本文枠に日本語が残っている: %s", kind, message.body())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("AC-5: 全引数が String のため MessageFormat の数値既定書式（3 桁区切り）は適用されない")
        void 数値書式は適用されない() {
            FanoutMessageRenderer renderer = newRenderer();
            // 数値に見える文字列を渡しても String として素通しされ、"1,234,567" のように桁区切りされない。
            String numericLooking = "1234567";
            for (FanoutMessageKind kind : FanoutMessageKind.values()) {
                if (kind.argCount() == 0) {
                    continue;
                }
                FanoutMessageRenderer.RenderedMessage message =
                        renderer.renderAllLocales(kind, numericLooking).get("en");
                assertThat(message.title() + message.body())
                        .as("%s: 引数は String なので桁区切りされない", kind)
                        .contains("1234567")
                        .doesNotContain("1,234,567");
            }
        }
    }

    // =================================================================
    // AC-6: 欠落キーは無音でフォールバックせず例外で落ちる
    // =================================================================

    @Nested
    @DisplayName("AC-6: 欠落キーは握り潰さず例外にする")
    class MissingKeyHardFails {

        @Test
        @DisplayName("AC-6: どのバンドルにも無いキーは NoSuchMessageException になる（キー文字列を本文として配らない）")
        void 欠落キーは例外になる() {
            MessageSource source = productionLikeMessageSource();
            assertThatThrownBy(() -> source.getMessage(
                    "notification.fanout.__absolutely.missing.key__", new Object[]{"x"}, DeliveryLocales.toLocale("en")))
                    .as("AC-6: 欠落キーを無音でキー文字列にフォールバックさせない")
                    .isInstanceOf(NoSuchMessageException.class);
        }

        @Test
        @DisplayName("AC-6(自己検証): 実在するキーは例外にならない（検出器が何でも落とすわけではない）")
        void 実在キーは例外にならない() {
            MessageSource source = productionLikeMessageSource();
            assertThat(source.getMessage(FanoutMessageKind.SHIFT_PUBLISHED.titleKey(),
                    new Object[0], DeliveryLocales.toLocale("en")))
                    .as("実在キーは正常に引ける（この番人が常に落ちるわけではない）")
                    .isNotBlank();
        }

        @Test
        @DisplayName("AC-6: レンダラは描画失敗を握り潰さず呼び出し元へ伝播させる")
        void レンダラは描画失敗を伝播させる() {
            MessageSource broken = new ReloadableResourceBundleMessageSource() {
                {
                    setBasenames("classpath:__no_such_bundle__");
                    setUseCodeAsDefaultMessage(false);
                    setFallbackToSystemLocale(false);
                }
            };
            FanoutMessageRenderer renderer = new FanoutMessageRenderer(broken, null);
            assertThatThrownBy(() -> renderer.renderAllLocales(FanoutMessageKind.SHIFT_PUBLISHED))
                    .as("AC-6: 文面を引けないまま「空の通知」を作って配ることは許さない")
                    .isInstanceOf(NoSuchMessageException.class);
        }
    }

    // =================================================================
    // AC-8: 切り詰めはコードポイント境界
    // =================================================================

    @Nested
    @DisplayName("AC-8: 切り詰めはコードポイント境界（サロゲートペアを割らない）")
    class Truncation {

        @Test
        @DisplayName("AC-8: 199 / 200 / 201 文字のタイトル境界")
        void タイトルの境界() {
            assertThat(FanoutMessageRenderer.truncateByCodePoints("あ".repeat(199), 200))
                    .as("上限未満はそのまま").hasSize(199);
            assertThat(FanoutMessageRenderer.truncateByCodePoints("あ".repeat(200), 200))
                    .as("ちょうど上限はそのまま").hasSize(200);
            assertThat(FanoutMessageRenderer.truncateByCodePoints("あ".repeat(201), 200))
                    .as("上限超過は上限まで切り詰める").hasSize(200);
        }

        @Test
        @DisplayName("AC-8: 999 / 1000 / 1001 文字の本文境界")
        void 本文の境界() {
            assertThat(FanoutMessageRenderer.truncateByCodePoints("a".repeat(999), 1000)).hasSize(999);
            assertThat(FanoutMessageRenderer.truncateByCodePoints("a".repeat(1000), 1000)).hasSize(1000);
            assertThat(FanoutMessageRenderer.truncateByCodePoints("a".repeat(1001), 1000)).hasSize(1000);
        }

        @Test
        @DisplayName("AC-8: 絵文字（サロゲートペア）の境界でペアを分断しない")
        void 絵文字の境界でペアを割らない() {
            // 🎆 は補助面の文字＝UTF-16 では 2 コードユニット・1 コードポイント。
            String emoji = "🎆";
            assertThat(emoji.length()).as("前提: この絵文字はサロゲートペア（2 コードユニット）").isEqualTo(2);

            // 上限 3 コードポイント ＝ 絵文字 3 個ぶん。4 個目は丸ごと落ちる。
            String four = emoji.repeat(4);
            String truncated = FanoutMessageRenderer.truncateByCodePoints(four, 3);

            assertThat(truncated).as("コードポイント 3 個ぶんに収まる").isEqualTo(emoji.repeat(3));
            assertThat(truncated.codePointCount(0, truncated.length()))
                    .as("コードポイント数が上限どおり").isEqualTo(3);
            assertThat(Character.isHighSurrogate(truncated.charAt(truncated.length() - 1)))
                    .as("末尾が孤立サロゲート（壊れた文字）になっていない")
                    .isFalse();
        }

        @Test
        @DisplayName("AC-8(自己検証): 素の substring なら同じ境界でサロゲートペアが割れる")
        void 自己検証_substringだと割れる() {
            String four = "🎆".repeat(4);
            // 素の substring はコードユニット単位。3 で切ると 2 個目のペアの前半だけが残る。
            String naive = four.substring(0, 3);
            assertThat(Character.isHighSurrogate(naive.charAt(naive.length() - 1)))
                    .as("番人が自分の偽陰性を晒すための自己検証: 素の substring は実際にペアを割る")
                    .isTrue();
        }

        @Test
        @DisplayName("AC-8: 描画結果は必ず列長（title 200 / body 1000）に収まる")
        void 描画結果は列長に収まる() {
            FanoutMessageRenderer renderer = newRenderer();
            String longName = "🎆".repeat(3000);
            for (FanoutMessageKind kind : FanoutMessageKind.values()) {
                for (FanoutMessageRenderer.RenderedMessage message :
                        renderer.renderAllLocales(kind, longName).values()) {
                    assertThat(message.title().codePointCount(0, message.title().length()))
                            .as("%s: title は 200 コードポイント以内", kind)
                            .isLessThanOrEqualTo(FanoutMessageRenderer.TITLE_MAX_CODE_POINTS);
                    assertThat(message.body().codePointCount(0, message.body().length()))
                            .as("%s: body は 1000 コードポイント以内", kind)
                            .isLessThanOrEqualTo(FanoutMessageRenderer.BODY_MAX_CODE_POINTS);
                }
            }
        }
    }
}
