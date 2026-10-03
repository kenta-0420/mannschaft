package com.mannschaft.app.common;

import com.mannschaft.app.schedule.CommentOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnumInputParserTest {

    @Test
    void 定義済み値は正規化せずに変換する() {
        assertThat(EnumInputParser.parse(CommentOption.class, "OPTIONAL", "commentOption"))
                .isEqualTo(CommentOption.OPTIONAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "optional"})
    void 空文字空白大小文字差の未定義値は入力エラーへ変換する(String value) {
        assertThatThrownBy(() -> EnumInputParser.parse(CommentOption.class, value, "commentOption"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException businessException = (BusinessException) ex;
                    assertThat(businessException.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_001);
                    assertThat(businessException.getFieldErrors()).singleElement().satisfies(fieldError -> {
                        assertThat(fieldError.getField()).isEqualTo("commentOption");
                        assertThat(fieldError.getMessage()).isEqualTo("定義されていない値です");
                    });
                });
    }

    @Test
    void nullは必須enum入力の入力エラーへ変換する() {
        assertThatThrownBy(() -> EnumInputParser.parse(CommentOption.class, null, "commentOption"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException businessException = (BusinessException) ex;
                    assertThat(businessException.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_001);
                    assertThat(businessException.getFieldErrors()).singleElement().satisfies(fieldError ->
                            assertThat(fieldError.getField()).isEqualTo("commentOption"));
                });
    }
}
