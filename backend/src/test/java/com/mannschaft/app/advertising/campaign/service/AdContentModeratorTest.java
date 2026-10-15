package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.entity.AdNgWord;
import com.mannschaft.app.advertising.campaign.enums.AdNgWordSeverity;
import com.mannschaft.app.advertising.campaign.repository.AdNgWordRepository;
import com.mannschaft.app.advertising.campaign.service.moderation.DetectedNgWord;
import com.mannschaft.app.advertising.campaign.service.moderation.ModerationCheckResult;
import com.mannschaft.app.advertising.campaign.service.moderation.SuggestedModerationAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

/**
 * F09.17 Phase 11-b {@link AdContentModerator} 単体テスト。
 *
 * <p>{@code @Cacheable} のキャッシュ層は単体テストでは介在しないため、
 * {@link AdNgWordRepository} のスタブ応答が毎回返る。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdContentModerator 単体テスト")
class AdContentModeratorTest {

    @Mock private AdNgWordRepository adNgWordRepository;
    @InjectMocks private AdContentModerator moderator;

    private static AdNgWord ngWord(String word, String category, AdNgWordSeverity severity) {
        AdNgWord entity = AdNgWord.builder()
                .word(word)
                .category(category)
                .severity(severity)
                .isActive(true)
                .build();
        entity.setId(UUID.randomUUID());
        return entity;
    }

    @BeforeEach
    void setUp() {
        // issue #2544: 本番では @Autowired @Lazy で注入される自己プロキシ self を UT では自分自身で埋める。
        org.springframework.test.util.ReflectionTestUtils.setField(moderator, "self", moderator);

        // デフォルト辞書: WARN「最高」「限定」、BLOCK「治る」「必ず儲かる」
        // 一部テストで上書きするため lenient() でスタブする
        lenient().when(adNgWordRepository.findByIsActiveTrue()).thenReturn(List.of(
                ngWord("最高", "SUPERLATIVE", AdNgWordSeverity.WARN),
                ngWord("限定", "OTHER", AdNgWordSeverity.WARN),
                ngWord("治る", "PHARMA", AdNgWordSeverity.BLOCK),
                ngWord("必ず儲かる", "FINANCIAL_RISK", AdNgWordSeverity.BLOCK)
        ));
    }

    @Test
    @DisplayName("check 検出なし: 健全本文 → AUTO_PASS、検出 0 件")
    void check_検出なし_AUTO_PASS() {
        ModerationCheckResult result = moderator.check("今週のおすすめ商品をご紹介します。");

        assertThat(result.detectedWords()).isEmpty();
        assertThat(result.suggestedAction()).isEqualTo(SuggestedModerationAction.AUTO_PASS);
    }

    @Test
    @DisplayName("check WARN 1 件: AUTO_FLAG + 検出ワード 1 件")
    void check_WARN_1件_AUTO_FLAG() {
        ModerationCheckResult result = moderator.check("当社の最高のサービスをぜひお試しください。");

        assertThat(result.suggestedAction()).isEqualTo(SuggestedModerationAction.AUTO_FLAG);
        assertThat(result.detectedWords())
                .hasSize(1)
                .extracting(DetectedNgWord::word, DetectedNgWord::severity)
                .containsExactly(org.assertj.core.api.Assertions.tuple("最高", AdNgWordSeverity.WARN));
    }

    @Test
    @DisplayName("check BLOCK 1 件: AUTO_BLOCK + 検出ワード 1 件")
    void check_BLOCK_1件_AUTO_BLOCK() {
        ModerationCheckResult result = moderator.check("この薬で頭痛が治る効果があります。");

        assertThat(result.suggestedAction()).isEqualTo(SuggestedModerationAction.AUTO_BLOCK);
        assertThat(result.detectedWords())
                .hasSize(1)
                .extracting(DetectedNgWord::word)
                .containsExactly("治る");
    }

    @Test
    @DisplayName("check WARN + BLOCK 混在: BLOCK 優先で AUTO_BLOCK")
    void check_混在_BLOCK優先() {
        ModerationCheckResult result = moderator.check("最高の薬で必ず儲かる投資ができます。");

        assertThat(result.suggestedAction()).isEqualTo(SuggestedModerationAction.AUTO_BLOCK);
        // 「最高」(WARN)と「必ず儲かる」(BLOCK)の 2 件が検出される
        assertThat(result.detectedWords()).hasSize(2);
        assertThat(result.detectedWords())
                .extracting(DetectedNgWord::word)
                .containsExactlyInAnyOrder("最高", "必ず儲かる");
    }

    @Test
    @DisplayName("check 大文字小文字無視: 半角英数の case insensitive 照合")
    void check_大文字小文字無視() {
        // 辞書に「BEST」を追加
        given(adNgWordRepository.findByIsActiveTrue()).willReturn(List.of(
                ngWord("BEST", "SUPERLATIVE", AdNgWordSeverity.WARN)
        ));

        ModerationCheckResult result = moderator.check("This is the best service in town.");

        assertThat(result.suggestedAction()).isEqualTo(SuggestedModerationAction.AUTO_FLAG);
        assertThat(result.detectedWords()).extracting(DetectedNgWord::word).containsExactly("BEST");
    }

    @Test
    @DisplayName("check Markdown コードブロック内も検査対象: コード断片に NG を埋めても検知")
    void check_Markdownコードブロック内も対象() {
        String markdown = """
                # 商品紹介

                通常テキストです。

                ```
                // この薬は治る、というコードコメント
                var msg = "完治しません";
                ```

                以上。
                """;

        ModerationCheckResult result = moderator.check(markdown);

        // 「治る」は辞書にあるので検知される (コードブロック前処理なし)
        assertThat(result.suggestedAction()).isEqualTo(SuggestedModerationAction.AUTO_BLOCK);
        assertThat(result.detectedWords())
                .extracting(DetectedNgWord::word)
                .contains("治る");
    }

    @Test
    @DisplayName("check 空文字/null: 何もチェックせず AUTO_PASS")
    void check_空文字_null() {
        assertThat(moderator.check("").suggestedAction()).isEqualTo(SuggestedModerationAction.AUTO_PASS);
        assertThat(moderator.check(null).suggestedAction()).isEqualTo(SuggestedModerationAction.AUTO_PASS);
        assertThat(moderator.check("").detectedWords()).isEmpty();
    }

    @Test
    @DisplayName("check 辞書が空: 何もヒットせず AUTO_PASS")
    void check_辞書が空() {
        given(adNgWordRepository.findByIsActiveTrue()).willReturn(List.of());

        ModerationCheckResult result = moderator.check("最高の薬で治る！必ず儲かる！");

        assertThat(result.suggestedAction()).isEqualTo(SuggestedModerationAction.AUTO_PASS);
        assertThat(result.detectedWords()).isEmpty();
    }
}
