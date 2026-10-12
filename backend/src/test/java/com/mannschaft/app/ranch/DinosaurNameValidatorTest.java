package com.mannschaft.app.ranch;

import com.mannschaft.app.ranch.service.DinosaurNameValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AC61: Unicode書記素、不可視文字、保存上限の試練。 */
class DinosaurNameValidatorTest {
    private final DinosaurNameValidator validator = new DinosaurNameValidator();

    @Test
    void 前後Unicode空白を除去しNFCで保存する() {
        assertThat(validator.normalize("\u3000か\u3099お\u3000")).isEqualTo("がお");
    }

    @ParameterizedTest
    @ValueSource(strings = {"恐", "恐竜のあばたー名です", "👨‍👩‍👧‍👦", "👍🏽", "🇯🇵", "a\u0301"})
    void 日本語と結合文字と絵文字を許可する(String input) {
        assertThat(validator.normalize(input)).isNotBlank();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\u3000", "\u200B", "a\nb", "a\rb", "a\tb", "a\u0000b", "12345678901"})
    void 空不可視制御文字と十一書記素を拒否する(String input) {
        assertThatThrownBy(() -> validator.normalize(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 長い結合列は一書記素でも保存上限で拒否する() {
        assertThatThrownBy(() -> validator.normalize("a" + "\u0301".repeat(161)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 十家族絵文字とIndic結合は十書記素で十一個は拒否する() {
        for (String cluster : new String[] {"👨‍👩‍👧‍👦", "क्ष", "🇯🇵"}) {
            assertThat(validator.normalize(cluster.repeat(10))).isEqualTo(cluster.repeat(10));
            assertThatThrownBy(() -> validator.normalize(cluster.repeat(11)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void 一書記素でもcodePoint上限とUTF8上限を独立に守る() {
        String maxCodePoints = "b" + "\u0301".repeat(159);
        assertThat(validator.normalize(maxCodePoints)).isEqualTo(maxCodePoints);
        assertThatThrownBy(() -> validator.normalize(maxCodePoints + "\u0301"))
                .isInstanceOf(IllegalArgumentException.class);
        String modifiers = "🏽".repeat(127);
        assertThat(validator.normalize("aaaa" + modifiers)).isEqualTo("aaaa" + modifiers);
        assertThatThrownBy(() -> validator.normalize("aaaaa" + modifiers))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 前後NBSPも除き不可視のみと制御と未対応surrogateを拒否する() {
        assertThat(validator.normalize("\u00a0ひかり\u00a0")).isEqualTo("ひかり");
        for (String input : new String[] {"\u2060", "\u3164", "\ufe0f", "\nひかり", "ひ\u2028かり", "ひ\u202eかり", "\ud800"}) {
            assertThatThrownBy(() -> validator.normalize(input)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void Gurmukhiのvirama列をIndic一般と誤って一書記素にまとめない() {
        String twoGraphemes = "ਕ੍ਕ";
        assertThat(validator.normalize(twoGraphemes.repeat(5))).isEqualTo(twoGraphemes.repeat(5));
        assertThatThrownBy(() -> validator.normalize(twoGraphemes.repeat(6)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
