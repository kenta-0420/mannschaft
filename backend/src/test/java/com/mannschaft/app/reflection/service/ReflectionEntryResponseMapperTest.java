package com.mannschaft.app.reflection.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.reflection.RecallDirection;
import com.mannschaft.app.reflection.ReflectionOutlineRevealLevel;
import com.mannschaft.app.reflection.dto.ReflectionEntryResponse;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * {@link ReflectionEntryResponseMapper} 単体テスト（F06.5・§3.2 マスク分離・AC-8）。
 *
 * <p>マスク中は本文をソースから詰めない（structuredContent=null・maskedHint のみ）。開示時は本文を載せる。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReflectionEntryResponseMapper 単体テスト（§3.2 / AC-8）")
class ReflectionEntryResponseMapperTest {

    @Mock private ReflectionMaskEvaluator maskEvaluator;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ReflectionContentSanitizer sanitizer;
    private ReflectionEntryResponseMapper mapper;

    private static final LocalDate TARGET = LocalDate.of(2026, 6, 1);

    private static void setId(Object entity, UUID id) {
        try {
            Field f = entity.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ReflectionEntryEntity entry() {
        ReflectionEntryEntity e = ReflectionEntryEntity.builder()
                .themeId(UUID.randomUUID()).userId(1L).targetDate(TARGET)
                .structuredContent("{\"main_theme\":\"秘密の本文\"}").version(0L).build();
        setId(e, UUID.randomUUID());
        return e;
    }

    private ReflectionThemeEntity theme() {
        ReflectionThemeEntity t = ReflectionThemeEntity.builder()
                .userId(1L).title("数学II").recallIntervalDays("1,3,7,14").build();
        setId(t, UUID.randomUUID());
        return t;
    }

    private void init() {
        sanitizer = new ReflectionContentSanitizer(objectMapper);
        mapper = new ReflectionEntryResponseMapper(
                maskEvaluator, sanitizer, new ReflectionMaskedCueExtractor(),
                new ReflectionMaskedOutlineExtractor());
    }

    /** TERM_CARD section を含む本文を持つエントリ。 */
    private ReflectionEntryEntity termCardEntry() {
        String json = "{\"main_theme\":\"英単語\",\"sections\":["
                + "{\"type\":\"TERM_CARD\",\"heading\":\"今日の単語\",\"cards\":["
                + "{\"term\":\"abandon\",\"meaning\":\"見捨てる\"}]}]}";
        ReflectionEntryEntity e = ReflectionEntryEntity.builder()
                .themeId(UUID.randomUUID()).userId(1L).targetDate(TARGET)
                .structuredContent(json).version(0L).build();
        setId(e, UUID.randomUUID());
        return e;
    }

    @Test
    @DisplayName("AC-8: マスク中は structuredContent=null かつ isMasked=true、maskedHint のみ")
    void masked_bodyIsNull() {
        init();
        ReflectionEntryEntity e = entry();
        ReflectionThemeEntity t = theme();
        LocalDate today = TARGET.plusDays(1);
        given(maskEvaluator.isMasked(e, t, today)).willReturn(true);
        given(maskEvaluator.dueRecallDates(any(), any(), any()))
                .willReturn(List.of(TARGET.plusDays(1)));

        ReflectionEntryResponse resp = mapper.toResponse(e, t, today);

        assertThat(resp.isMasked()).isTrue();
        assertThat(resp.structuredContent()).isNull();
        assertThat(resp.maskedHint()).isNotNull();
        assertThat(resp.maskedHint().themeTitle()).isEqualTo("数学II");
        assertThat(resp.maskedHint().dueRecallDates()).containsExactly(TARGET.plusDays(1));
    }

    @Test
    @DisplayName("非マスク時は structuredContent を載せ isMasked=false、maskedHint=null")
    void revealed_bodyPresent() {
        init();
        ReflectionEntryEntity e = entry();
        ReflectionThemeEntity t = theme();
        given(maskEvaluator.isMasked(e, t, TARGET)).willReturn(false);

        ReflectionEntryResponse resp = mapper.toResponse(e, t, TARGET);

        assertThat(resp.isMasked()).isFalse();
        assertThat(resp.structuredContent()).isNotNull();
        assertThat(resp.structuredContent().get("main_theme").asText()).isEqualTo("秘密の本文");
        assertThat(resp.maskedHint()).isNull();
    }

    @Test
    @DisplayName("toRevealedResponse: マスクを無視して本文を開示（recall 開示の遷移点・AC-7）")
    void toRevealedResponse_disclosesBody() {
        init();
        ReflectionEntryEntity e = entry();
        ReflectionThemeEntity t = theme();

        ReflectionEntryResponse resp = mapper.toRevealedResponse(e, t);

        assertThat(resp.isMasked()).isFalse();
        assertThat(resp.structuredContent().get("main_theme").asText()).isEqualTo("秘密の本文");
    }

    @Test
    @DisplayName("AC-53: recall 後の toRevealedResponse は TERM_CARD の term/meaning 両側を開示する")
    void toRevealedResponse_termCard_disclosesBothSides() {
        init();
        ReflectionEntryEntity e = termCardEntry(); // term=abandon / meaning=見捨てる
        ReflectionThemeEntity t = theme();

        ReflectionEntryResponse resp = mapper.toRevealedResponse(e, t);

        assertThat(resp.isMasked()).isFalse();
        // recall 開示では cue だけでなく original 全文（両側）が structuredContent に載る（AC-53）。
        JsonNode card = resp.structuredContent().get("sections").get(0).get("cards").get(0);
        assertThat(card.get("term").asText()).isEqualTo("abandon");
        assertThat(card.get("meaning").asText()).isEqualTo("見捨てる");
        // 開示応答は maskedHint を持たない（cue 制限の対象外）。
        assertThat(resp.maskedHint()).isNull();
    }

    // ===== Phase 4: マスク中の TERM_CARD cue（§13-C / AC-51 / AC-52） =====

    @Test
    @DisplayName("AC-51/AC-52: マスク中 MEANING_TO_TERM では cardQuiz に meaning だけ。term(答え)が漏れない")
    void masked_termCard_meaningToTerm_noLeak() {
        init();
        ReflectionEntryEntity e = termCardEntry();
        ReflectionThemeEntity t = theme();
        LocalDate today = TARGET.plusDays(1);
        given(maskEvaluator.isMasked(e, t, today)).willReturn(true);
        given(maskEvaluator.dueRecallDates(any(), any(), any()))
                .willReturn(List.of(TARGET.plusDays(1)));
        given(maskEvaluator.resolveDirection(e, t, today))
                .willReturn(RecallDirection.MEANING_TO_TERM);

        ReflectionEntryResponse resp = mapper.toResponse(e, t, today);

        assertThat(resp.isMasked()).isTrue();
        assertThat(resp.structuredContent()).isNull();
        assertThat(resp.maskedHint().recallDirection()).isEqualTo(RecallDirection.MEANING_TO_TERM);
        assertThat(resp.maskedHint().cardQuiz()).hasSize(1);
        assertThat(resp.maskedHint().cardQuiz().get(0).prompts().get(0).promptText())
                .isEqualTo("見捨てる");
        // 答え（term=abandon）がペイロード文字列に一切現れない（漏洩ゼロ）。
        assertThat(resp.maskedHint().toString()).doesNotContain("abandon");
    }

    @Test
    @DisplayName("AC-51: マスク中 TERM_TO_MEANING では cue=term のみ。meaning(答え)が漏れない")
    void masked_termCard_termToMeaning_noLeak() {
        init();
        ReflectionEntryEntity e = termCardEntry();
        ReflectionThemeEntity t = theme();
        LocalDate today = TARGET.plusDays(3);
        given(maskEvaluator.isMasked(e, t, today)).willReturn(true);
        given(maskEvaluator.dueRecallDates(any(), any(), any())).willReturn(List.of());
        given(maskEvaluator.resolveDirection(e, t, today))
                .willReturn(RecallDirection.TERM_TO_MEANING);

        ReflectionEntryResponse resp = mapper.toResponse(e, t, today);

        assertThat(resp.maskedHint().cardQuiz().get(0).prompts().get(0).promptText())
                .isEqualTo("abandon");
        assertThat(resp.maskedHint().toString()).doesNotContain("見捨てる");
    }

    @Test
    @DisplayName("AC-51: today=null（fail-closed 経路）では cardQuiz 空・recallDirection=null")
    void masked_todayNull_failClosed() {
        init();
        ReflectionEntryEntity e = termCardEntry();
        ReflectionThemeEntity t = theme();
        // パース不能本文で revealedResponse→maskedResponse(today=null) 経路を踏ませる。
        ReflectionEntryEntity broken = ReflectionEntryEntity.builder()
                .themeId(UUID.randomUUID()).userId(1L).targetDate(TARGET)
                .structuredContent("not-json").version(0L).build();
        setId(broken, UUID.randomUUID());
        given(maskEvaluator.isMasked(broken, t, TARGET)).willReturn(false);

        ReflectionEntryResponse resp = mapper.toResponse(broken, t, TARGET);

        assertThat(resp.isMasked()).isTrue();
        assertThat(resp.structuredContent()).isNull();
        assertThat(resp.maskedHint().recallDirection()).isNull();
        assertThat(resp.maskedHint().cardQuiz()).isEmpty();
    }

    // ===== §13-C 増分: OUTLINE 段階式マスク（足場ラダー・AC-81/82/83/85/89） =====

    /** OUTLINE section（heading + 小見出し/詳細/補足）を持つエントリ。 */
    private ReflectionEntryEntity outlineEntry() {
        String json = "{\"main_theme\":\"二次関数の最大最小\",\"sections\":["
                + "{\"type\":\"OUTLINE\",\"heading\":\"今日のポイント\",\"subsections\":["
                + "{\"sub_heading\":\"頂点SECRET\",\"detail\":\"詳細SECRET\",\"supplement\":\"補足SECRET\"}]}]}";
        ReflectionEntryEntity e = ReflectionEntryEntity.builder()
                .themeId(UUID.randomUUID()).userId(1L).targetDate(TARGET)
                .structuredContent(json).version(0L).build();
        setId(e, UUID.randomUUID());
        return e;
    }

    @Test
    @DisplayName("AC-81/AC-89: マスク中 FULL は main_theme/heading 全文。詳細/補足は漏れない")
    void masked_outlineScaffold_full() {
        init();
        ReflectionEntryEntity e = outlineEntry();
        ReflectionThemeEntity t = theme();
        LocalDate today = TARGET.plusDays(1);
        given(maskEvaluator.isMasked(e, t, today)).willReturn(true);
        given(maskEvaluator.dueRecallDates(any(), any(), any())).willReturn(List.of());
        given(maskEvaluator.resolveOutlineRevealLevel(e, t, today))
                .willReturn(ReflectionOutlineRevealLevel.FULL);

        ReflectionEntryResponse resp = mapper.toResponse(e, t, today);

        assertThat(resp.isMasked()).isTrue();
        assertThat(resp.structuredContent()).isNull();
        ReflectionEntryResponse.MaskedOutlineScaffold scaffold = resp.maskedHint().outlineScaffold();
        assertThat(scaffold.level()).isEqualTo(ReflectionOutlineRevealLevel.FULL);
        assertThat(scaffold.mainTheme()).isEqualTo("二次関数の最大最小");
        assertThat(scaffold.sections().get(0).heading()).isEqualTo("今日のポイント");
        // 詳細/補足/小見出し（答え側）はペイロード文字列に現れない。
        String dump = resp.maskedHint().toString();
        assertThat(dump).doesNotContain("頂点SECRET");
        assertThat(dump).doesNotContain("詳細SECRET");
        assertThat(dump).doesNotContain("補足SECRET");
    }

    @Test
    @DisplayName("AC-82: マスク中 PARTIAL は main_theme/heading が先頭3コードポイント。4字目以降は漏れない")
    void masked_outlineScaffold_partial() {
        init();
        ReflectionEntryEntity e = outlineEntry();
        ReflectionThemeEntity t = theme();
        LocalDate today = TARGET.plusDays(7);
        given(maskEvaluator.isMasked(e, t, today)).willReturn(true);
        given(maskEvaluator.dueRecallDates(any(), any(), any())).willReturn(List.of());
        given(maskEvaluator.resolveOutlineRevealLevel(e, t, today))
                .willReturn(ReflectionOutlineRevealLevel.PARTIAL);

        ReflectionEntryResponse resp = mapper.toResponse(e, t, today);

        ReflectionEntryResponse.MaskedOutlineScaffold scaffold = resp.maskedHint().outlineScaffold();
        assertThat(scaffold.level()).isEqualTo(ReflectionOutlineRevealLevel.PARTIAL);
        assertThat(scaffold.mainTheme()).isEqualTo("二次関");
        assertThat(scaffold.sections().get(0).heading()).isEqualTo("今日の");
        // 4 字目以降は漏れない。
        String dump = resp.maskedHint().toString();
        assertThat(dump).doesNotContain("ポイント");
        assertThat(dump).doesNotContain("詳細SECRET");
    }

    @Test
    @DisplayName("AC-83: マスク中 HIDDEN（k≥4）は足場ゼロ（mainTheme=null・sections 空＝従来完全マスク等価）")
    void masked_outlineScaffold_hidden() {
        init();
        ReflectionEntryEntity e = outlineEntry();
        ReflectionThemeEntity t = theme();
        LocalDate today = TARGET.plusDays(14);
        given(maskEvaluator.isMasked(e, t, today)).willReturn(true);
        given(maskEvaluator.dueRecallDates(any(), any(), any())).willReturn(List.of());
        given(maskEvaluator.resolveOutlineRevealLevel(e, t, today))
                .willReturn(ReflectionOutlineRevealLevel.HIDDEN);

        ReflectionEntryResponse resp = mapper.toResponse(e, t, today);

        ReflectionEntryResponse.MaskedOutlineScaffold scaffold = resp.maskedHint().outlineScaffold();
        assertThat(scaffold.level()).isEqualTo(ReflectionOutlineRevealLevel.HIDDEN);
        assertThat(scaffold.mainTheme()).isNull();
        assertThat(scaffold.sections()).isEmpty();
        // 本文・足場とも漏れない。
        assertThat(resp.maskedHint().toString()).doesNotContain("二次関");
    }

    @Test
    @DisplayName("AC-85: today=null（fail-closed 経路）では outlineScaffold が HIDDEN 空")
    void masked_outlineScaffold_todayNull_failClosed() {
        init();
        ReflectionThemeEntity t = theme();
        ReflectionEntryEntity broken = ReflectionEntryEntity.builder()
                .themeId(UUID.randomUUID()).userId(1L).targetDate(TARGET)
                .structuredContent("not-json").version(0L).build();
        setId(broken, UUID.randomUUID());
        given(maskEvaluator.isMasked(broken, t, TARGET)).willReturn(false);

        ReflectionEntryResponse resp = mapper.toResponse(broken, t, TARGET);

        ReflectionEntryResponse.MaskedOutlineScaffold scaffold = resp.maskedHint().outlineScaffold();
        assertThat(scaffold.level()).isEqualTo(ReflectionOutlineRevealLevel.HIDDEN);
        assertThat(scaffold.mainTheme()).isNull();
        assertThat(scaffold.sections()).isEmpty();
    }

    @Test
    @DisplayName("AC-56: OUTLINE のみのエントリのマスク挙動は従来どおり（cardQuiz 空・本文 null）")
    void masked_outlineOnly_regression() {
        init();
        ReflectionEntryEntity e = entry(); // main_theme のみ（OUTLINE 相当・cards 無し）
        ReflectionThemeEntity t = theme();
        LocalDate today = TARGET.plusDays(1);
        given(maskEvaluator.isMasked(e, t, today)).willReturn(true);
        given(maskEvaluator.dueRecallDates(any(), any(), any()))
                .willReturn(List.of(TARGET.plusDays(1)));
        given(maskEvaluator.resolveDirection(e, t, today))
                .willReturn(RecallDirection.MEANING_TO_TERM);

        ReflectionEntryResponse resp = mapper.toResponse(e, t, today);

        assertThat(resp.structuredContent()).isNull();
        assertThat(resp.maskedHint().cardQuiz()).isEmpty(); // TERM_CARD が無いので cue も無い
    }

    @Test
    @DisplayName("AC-50/AC-56: 旧形 JSON（type/cards 欠落）でもマスク中に壊れず cardQuiz 空（読取後方互換番人）")
    void masked_legacyJson_backwardCompatible() {
        init();
        String legacy = "{\"main_theme\":\"既存\",\"sections\":["
                + "{\"heading\":\"平方完成\",\"subsections\":["
                + "{\"sub_heading\":\"基本形\",\"detail\":\"頂点を読む\"}]}]}";
        ReflectionEntryEntity e = ReflectionEntryEntity.builder()
                .themeId(UUID.randomUUID()).userId(1L).targetDate(TARGET)
                .structuredContent(legacy).version(0L).build();
        setId(e, UUID.randomUUID());
        ReflectionThemeEntity t = theme();
        LocalDate today = TARGET.plusDays(1);
        given(maskEvaluator.isMasked(e, t, today)).willReturn(true);
        given(maskEvaluator.dueRecallDates(any(), any(), any())).willReturn(List.of());
        given(maskEvaluator.resolveDirection(e, t, today))
                .willReturn(RecallDirection.MEANING_TO_TERM);

        ReflectionEntryResponse resp = mapper.toResponse(e, t, today);

        assertThat(resp.structuredContent()).isNull();
        assertThat(resp.maskedHint().cardQuiz()).isEmpty(); // 旧形=OUTLINE 扱いで cue 無し
        assertThat(resp.maskedHint().toString()).doesNotContain("頂点を読む");
    }
}
