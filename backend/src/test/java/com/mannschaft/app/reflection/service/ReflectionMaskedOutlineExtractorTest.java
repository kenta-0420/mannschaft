package com.mannschaft.app.reflection.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mannschaft.app.reflection.ReflectionOutlineRevealLevel;
import com.mannschaft.app.reflection.dto.ReflectionEntryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReflectionMaskedOutlineExtractor} 単体テスト（F06.5・§13-C 増分・足場ラダー・AC-81/82/83/87/88/89）。
 *
 * <p><b>セキュリティ核心の番人</b>: 足場として載せてよいのは {@code main_theme} と OUTLINE section の
 * {@code heading} のみ。<b>小見出し（sub_heading）・詳細（detail）・補足（supplement）はどの開示レベルでも
 * 出力に一切現れない</b>。PARTIAL はサーバ側で先頭 3 コードポイントに切る（日本語マルチバイト・サロゲートペア安全）。
 * parse 不能・null・型不整合は HIDDEN（足場ゼロ）にフォールバック（fail-closed）。</p>
 */
@DisplayName("ReflectionMaskedOutlineExtractor 単体テスト（§13-C 足場ラダー・漏洩番人）")
class ReflectionMaskedOutlineExtractorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReflectionMaskedOutlineExtractor extractor = new ReflectionMaskedOutlineExtractor();

    /**
     * OUTLINE section（heading + 小見出し/詳細/補足）を 1 つ持つ本文を作る。
     * 小見出し/詳細/補足は「漏れてはいけない答え側」の番人ワード。
     */
    private JsonNode outlineContent(String mainTheme, String heading) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("main_theme", mainTheme);
        ArrayNode sections = root.putArray("sections");
        ObjectNode section = sections.addObject();
        section.put("type", "OUTLINE");
        section.put("heading", heading);
        ArrayNode subs = section.putArray("subsections");
        ObjectNode sub = subs.addObject();
        sub.put("sub_heading", "答え小見出しSECRET");
        sub.put("detail", "答え詳細SECRET");
        sub.put("supplement", "答え補足SECRET");
        return root;
    }

    private void assertNoAnswerSideLeak(ReflectionEntryResponse.MaskedOutlineScaffold scaffold) {
        String dump = scaffold.toString();
        assertThat(dump).doesNotContain("答え小見出しSECRET");
        assertThat(dump).doesNotContain("答え詳細SECRET");
        assertThat(dump).doesNotContain("答え補足SECRET");
    }

    @Test
    @DisplayName("AC-81: FULL は main_theme・heading 全文。小見出し/詳細/補足は出力に無い")
    void full_headingsFull_noSubLeak() {
        JsonNode content = outlineContent("二次関数の最大最小", "今日のポイント");

        ReflectionEntryResponse.MaskedOutlineScaffold scaffold =
                extractor.extractScaffold(content, ReflectionOutlineRevealLevel.FULL);

        assertThat(scaffold.level()).isEqualTo(ReflectionOutlineRevealLevel.FULL);
        assertThat(scaffold.mainTheme()).isEqualTo("二次関数の最大最小");
        assertThat(scaffold.sections()).hasSize(1);
        assertThat(scaffold.sections().get(0).heading()).isEqualTo("今日のポイント");
        assertNoAnswerSideLeak(scaffold);
    }

    @Test
    @DisplayName("AC-82/AC-88: PARTIAL は main_theme・heading を先頭3コードポイント。4字目以降が出力に無い")
    void partial_truncatedToThreeCodePoints_noTailLeak() {
        // main_theme="二次関数の最大最小"（9 文字）→ "二次関"。heading="今日のポイント"（7 文字）→ "今日の"。
        JsonNode content = outlineContent("二次関数の最大最小", "今日のポイント");

        ReflectionEntryResponse.MaskedOutlineScaffold scaffold =
                extractor.extractScaffold(content, ReflectionOutlineRevealLevel.PARTIAL);

        assertThat(scaffold.level()).isEqualTo(ReflectionOutlineRevealLevel.PARTIAL);
        assertThat(scaffold.mainTheme()).isEqualTo("二次関");
        assertThat(scaffold.sections().get(0).heading()).isEqualTo("今日の");
        // 4 字目以降（答えに近い続き）はペイロードに出ない。
        String dump = scaffold.toString();
        assertThat(dump).doesNotContain("数"); // main_theme 4 字目
        assertThat(dump).doesNotContain("ポイント"); // heading 4 字目以降
        assertNoAnswerSideLeak(scaffold);
    }

    @Test
    @DisplayName("AC-88: 見出しが3コードポイント以下なら全文（切り詰めない）")
    void partial_shortHeadingKeptWhole() {
        JsonNode content = outlineContent("AB", "X");

        ReflectionEntryResponse.MaskedOutlineScaffold scaffold =
                extractor.extractScaffold(content, ReflectionOutlineRevealLevel.PARTIAL);

        assertThat(scaffold.mainTheme()).isEqualTo("AB");
        assertThat(scaffold.sections().get(0).heading()).isEqualTo("X");
    }

    @Test
    @DisplayName("AC-88: PARTIAL の3コードポイント truncate はサロゲートペア安全（絵文字を割らない）")
    void partial_surrogatePairSafe() {
        // 絵文字 4 つ（各 1 コードポイント・UTF-16 では各 2 char）→ 先頭 3 コードポイント。
        String emoji = "😀😁😂😃"; // 😀😁😂😃
        String expected = "😀😁😂"; // 😀😁😂（3 コードポイント）
        JsonNode content = outlineContent(emoji, emoji);

        ReflectionEntryResponse.MaskedOutlineScaffold scaffold =
                extractor.extractScaffold(content, ReflectionOutlineRevealLevel.PARTIAL);

        assertThat(scaffold.mainTheme()).isEqualTo(expected);
        assertThat(scaffold.sections().get(0).heading()).isEqualTo(expected);
        // 4 つ目の絵文字は出ない。
        assertThat(scaffold.toString()).doesNotContain("😃");
        // 壊れた半サロゲートで終わらない（コードポイント単位で切れている）。
        assertThat(scaffold.mainTheme().codePointCount(0, scaffold.mainTheme().length())).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-83: HIDDEN は空（mainTheme=null・sections 空・小見出し等も無し）")
    void hidden_empty() {
        JsonNode content = outlineContent("二次関数の最大最小", "今日のポイント");

        ReflectionEntryResponse.MaskedOutlineScaffold scaffold =
                extractor.extractScaffold(content, ReflectionOutlineRevealLevel.HIDDEN);

        assertThat(scaffold.level()).isEqualTo(ReflectionOutlineRevealLevel.HIDDEN);
        assertThat(scaffold.mainTheme()).isNull();
        assertThat(scaffold.sections()).isEmpty();
        // 全文も先頭も漏れない。
        assertThat(scaffold.toString()).doesNotContain("二次関");
        assertNoAnswerSideLeak(scaffold);
    }

    @Test
    @DisplayName("AC-87: TERM_CARD section は足場に含めない（OUTLINE のみ抽出・cards 本文も漏れない）")
    void termCardSkipped_outlineOnly() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("main_theme", "英単語Unit5");
        ArrayNode sections = root.putArray("sections");
        // OUTLINE section
        ObjectNode outline = sections.addObject();
        outline.put("type", "OUTLINE");
        outline.put("heading", "今日のまとめ");
        // TERM_CARD section（足場に出てはいけない）
        ObjectNode termCard = sections.addObject();
        termCard.put("type", "TERM_CARD");
        termCard.put("heading", "今日の単語");
        ArrayNode cards = termCard.putArray("cards");
        cards.addObject().put("term", "abandonSECRET").put("meaning", "見捨てるSECRET");

        ReflectionEntryResponse.MaskedOutlineScaffold scaffold =
                extractor.extractScaffold(root, ReflectionOutlineRevealLevel.FULL);

        // OUTLINE section だけが足場になる（TERM_CARD の heading も含めない）。
        assertThat(scaffold.sections()).hasSize(1);
        assertThat(scaffold.sections().get(0).heading()).isEqualTo("今日のまとめ");
        String dump = scaffold.toString();
        assertThat(dump).doesNotContain("今日の単語"); // TERM_CARD の heading
        assertThat(dump).doesNotContain("abandonSECRET"); // term
        assertThat(dump).doesNotContain("見捨てるSECRET"); // meaning
    }

    @Test
    @DisplayName("AC-89: 混在エントリでも詳細/補足の語が足場に現れない（番人）")
    void mixed_noDetailSupplementLeak() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("main_theme", "テーマ");
        ArrayNode sections = root.putArray("sections");
        ObjectNode outline = sections.addObject();
        outline.put("type", "OUTLINE");
        outline.put("heading", "見出し");
        ArrayNode subs = outline.putArray("subsections");
        ObjectNode sub = subs.addObject();
        sub.put("sub_heading", "subHeadingSECRET");
        sub.put("detail", "detailSECRET");
        sub.put("supplement", "supplementSECRET");
        ObjectNode termCard = sections.addObject();
        termCard.put("type", "TERM_CARD");
        termCard.put("heading", "カード見出し");

        for (ReflectionOutlineRevealLevel level : ReflectionOutlineRevealLevel.values()) {
            ReflectionEntryResponse.MaskedOutlineScaffold scaffold =
                    extractor.extractScaffold(root, level);
            String dump = scaffold.toString();
            assertThat(dump).doesNotContain("subHeadingSECRET");
            assertThat(dump).doesNotContain("detailSECRET");
            assertThat(dump).doesNotContain("supplementSECRET");
        }
    }

    @Test
    @DisplayName("AC-86: null content / 非オブジェクト / null level は HIDDEN 空（fail-closed）")
    void nullOrNonObject_failClosedHidden() {
        ReflectionEntryResponse.MaskedOutlineScaffold s1 =
                extractor.extractScaffold(null, ReflectionOutlineRevealLevel.FULL);
        assertThat(s1.level()).isEqualTo(ReflectionOutlineRevealLevel.HIDDEN);
        assertThat(s1.mainTheme()).isNull();
        assertThat(s1.sections()).isEmpty();

        ReflectionEntryResponse.MaskedOutlineScaffold s2 =
                extractor.extractScaffold(objectMapper.createArrayNode(),
                        ReflectionOutlineRevealLevel.FULL);
        assertThat(s2.sections()).isEmpty();

        // null level は HIDDEN 扱い（fail-closed）。
        JsonNode content = outlineContent("二次関数", "見出し");
        ReflectionEntryResponse.MaskedOutlineScaffold s3 = extractor.extractScaffold(content, null);
        assertThat(s3.level()).isEqualTo(ReflectionOutlineRevealLevel.HIDDEN);
        assertThat(s3.mainTheme()).isNull();
        assertThat(s3.sections()).isEmpty();
    }

    @Test
    @DisplayName("AC-86: 型不整合（sections が配列でない）は HIDDEN 空（fail-closed）")
    void malformedSections_failClosed() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("main_theme", "テーマ");
        root.put("sections", "これは配列ではない"); // 型不整合

        ReflectionEntryResponse.MaskedOutlineScaffold scaffold =
                extractor.extractScaffold(root, ReflectionOutlineRevealLevel.FULL);

        // 型不整合は足場を一切出さない（fail-closed・AC-86）。
        assertThat(scaffold.level()).isEqualTo(ReflectionOutlineRevealLevel.HIDDEN);
        assertThat(scaffold.mainTheme()).isNull();
        assertThat(scaffold.sections()).isEmpty();
    }
}
