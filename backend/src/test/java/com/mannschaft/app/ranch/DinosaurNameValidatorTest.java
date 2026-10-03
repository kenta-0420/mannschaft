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
}
