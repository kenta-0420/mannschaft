package com.mannschaft.app.match.catalog;

import com.mannschaft.app.match.domain.MatchEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CardReasonCatalog}（card_reason_code ↔ event_type 対応・サッカー）の整合テスト。
 *
 * <p>設計: sports/01_soccer.md §5（C1〜C8 / S1〜S6 / CS）/ §5.3 対応表 / 03 §C.4b 検証規約</p>
 */
@DisplayName("CardReasonCatalog 整合テスト（card_reason_code ↔ event_type）")
class CardReasonCatalogTest {

    @Test
    @DisplayName("YELLOW_CARD は CautionCode C1〜C8 を許容する")
    void yellowCardAllowsCautionCodes() {
        assertThat(CardReasonCatalog.allowedCodes(MatchEventType.YELLOW_CARD))
                .containsExactlyInAnyOrder("C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8");
        for (CautionCode c : CautionCode.values()) {
            assertThat(CardReasonCatalog.isValid(MatchEventType.YELLOW_CARD, c.name()))
                    .as("YELLOW_CARD は %s を許容", c).isTrue();
        }
    }

    @Test
    @DisplayName("RED_CARD は SendingOffCode S1〜S6 を許容し CS は許容しない（一発退場は警告2回でない）")
    void redCardAllowsS1ToS6ButNotCs() {
        assertThat(CardReasonCatalog.allowedCodes(MatchEventType.RED_CARD))
                .containsExactlyInAnyOrder("S1", "S2", "S3", "S4", "S5", "S6");
        assertThat(CardReasonCatalog.isValid(MatchEventType.RED_CARD, "CS"))
                .as("一発退場に CS は不整合").isFalse();
        assertThat(CardReasonCatalog.isValid(MatchEventType.RED_CARD, SendingOffCode.S2.name())).isTrue();
    }

    @Test
    @DisplayName("SECOND_YELLOW は CS のみ許容する（警告2回による退場）")
    void secondYellowAllowsOnlyCs() {
        assertThat(CardReasonCatalog.allowedCodes(MatchEventType.SECOND_YELLOW))
                .containsExactly("CS");
        assertThat(CardReasonCatalog.isValid(MatchEventType.SECOND_YELLOW, "CS")).isTrue();
        assertThat(CardReasonCatalog.isValid(MatchEventType.SECOND_YELLOW, "S1"))
                .as("SECOND_YELLOW に S1 は不整合").isFalse();
        assertThat(CardReasonCatalog.isValid(MatchEventType.SECOND_YELLOW, "C1"))
                .as("SECOND_YELLOW に C1 は不整合（card_reason_code は CS が正）").isFalse();
    }

    @Test
    @DisplayName("カード非対象 event_type への非NULLコード付与は不整合（許容コードは空集合）")
    void nonCardEventTypeRejectsCode() {
        assertThat(CardReasonCatalog.allowedCodes(MatchEventType.GOAL)).isEmpty();
        assertThat(CardReasonCatalog.isValid(MatchEventType.GOAL, "C1"))
                .as("GOAL に理由コードは不整合").isFalse();
        assertThat(CardReasonCatalog.isValid(MatchEventType.SUB_IN, "S1")).isFalse();
    }

    @Test
    @DisplayName("理由コードは任意（NULL は常に整合）")
    void nullCodeAlwaysValid() {
        assertThat(CardReasonCatalog.isValid(MatchEventType.YELLOW_CARD, null)).isTrue();
        assertThat(CardReasonCatalog.isValid(MatchEventType.GOAL, null))
                .as("カード非対象でも NULL は整合（コード非付与）").isTrue();
    }

    @Test
    @DisplayName("未知のコード文字列は不整合")
    void unknownCodeInvalid() {
        assertThat(CardReasonCatalog.isValid(MatchEventType.YELLOW_CARD, "X9")).isFalse();
        assertThat(CardReasonCatalog.isValid(MatchEventType.RED_CARD, "")).isFalse();
    }

    @Test
    @DisplayName("CautionCode は 8 値・SendingOffCode は 7 値（S1〜S6・CS）")
    void codeEnumCardinalities() {
        assertThat(CautionCode.values()).hasSize(8);
        assertThat(SendingOffCode.values()).hasSize(7);
    }
}
