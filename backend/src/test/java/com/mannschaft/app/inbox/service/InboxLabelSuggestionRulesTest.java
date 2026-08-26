package com.mannschaft.app.inbox.service;

import com.mannschaft.app.inbox.InboxLabelSuggestion;
import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F04.11 {@link InboxLabelSuggestionRules} 単体テスト（純関数・案C 静的ルール）。
 *
 * <p>設計書 03_business_logic.md §9 の提案ルール表を受け入れ条件化する。
 * 1 アイテムあたり提案は最大 1 件・非永続・状態なし。</p>
 */
@DisplayName("InboxLabelSuggestionRules 単体テスト")
class InboxLabelSuggestionRulesTest {

    private final InboxLabelSuggestionRules rules = new InboxLabelSuggestionRules();

    @Test
    @DisplayName("MENTION は priority 不問で REPLY_NEEDED")
    void mention() {
        assertThat(rules.suggest(InboxSourceType.MENTION, InboxPriority.LOW))
                .containsExactly(InboxLabelSuggestion.REPLY_NEEDED);
        assertThat(rules.suggest(InboxSourceType.MENTION, InboxPriority.URGENT))
                .containsExactly(InboxLabelSuggestion.REPLY_NEEDED);
    }

    @Test
    @DisplayName("ANNOUNCEMENT は priority 不問で READ_LATER")
    void announcement() {
        assertThat(rules.suggest(InboxSourceType.ANNOUNCEMENT, InboxPriority.NORMAL))
                .containsExactly(InboxLabelSuggestion.READ_LATER);
    }

    @Test
    @DisplayName("CONFIRMABLE は URGENT/HIGH のみ ACTION_NEEDED、それ以外は提案なし")
    void confirmable() {
        assertThat(rules.suggest(InboxSourceType.CONFIRMABLE, InboxPriority.URGENT))
                .containsExactly(InboxLabelSuggestion.ACTION_NEEDED);
        assertThat(rules.suggest(InboxSourceType.CONFIRMABLE, InboxPriority.HIGH))
                .containsExactly(InboxLabelSuggestion.ACTION_NEEDED);
        assertThat(rules.suggest(InboxSourceType.CONFIRMABLE, InboxPriority.NORMAL)).isEmpty();
        assertThat(rules.suggest(InboxSourceType.CONFIRMABLE, InboxPriority.LOW)).isEmpty();
    }

    @Test
    @DisplayName("TODO_DUE は URGENT（期限切れ）のみ URGENT、それ以外は提案なし")
    void todoDue() {
        assertThat(rules.suggest(InboxSourceType.TODO_DUE, InboxPriority.URGENT))
                .containsExactly(InboxLabelSuggestion.URGENT);
        assertThat(rules.suggest(InboxSourceType.TODO_DUE, InboxPriority.HIGH)).isEmpty();
        assertThat(rules.suggest(InboxSourceType.TODO_DUE, InboxPriority.NORMAL)).isEmpty();
    }

    @Test
    @DisplayName("NOTIFICATION は URGENT のみ ACTION_NEEDED、それ以外は提案なし")
    void notification() {
        assertThat(rules.suggest(InboxSourceType.NOTIFICATION, InboxPriority.URGENT))
                .containsExactly(InboxLabelSuggestion.ACTION_NEEDED);
        assertThat(rules.suggest(InboxSourceType.NOTIFICATION, InboxPriority.HIGH)).isEmpty();
        assertThat(rules.suggest(InboxSourceType.NOTIFICATION, InboxPriority.NORMAL)).isEmpty();
    }

    @Test
    @DisplayName("提案は最大 1 件に絞られる（提案過多を避ける＝ADHD 要件）")
    void atMostOneSuggestion() {
        for (InboxSourceType type : InboxSourceType.values()) {
            for (InboxPriority p : InboxPriority.values()) {
                assertThat(rules.suggest(type, p)).hasSizeLessThanOrEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("null 引数は安全に空リスト")
    void nullSafe() {
        assertThat(rules.suggest(null, InboxPriority.URGENT)).isEmpty();
        assertThat(rules.suggest(InboxSourceType.MENTION, null)).isEmpty();
    }

    @Test
    @DisplayName("各提案キーは既定色（#RRGGBB）と既定名を持つ")
    void suggestionKeysHaveDefaults() {
        for (InboxLabelSuggestion s : InboxLabelSuggestion.values()) {
            assertThat(s.defaultColor()).matches("^#[0-9A-Fa-f]{6}$");
            assertThat(s.defaultName()).isNotBlank();
        }
    }
}
