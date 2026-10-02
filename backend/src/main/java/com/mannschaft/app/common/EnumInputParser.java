package com.mannschaft.app.common;

import java.util.List;

/**
 * HTTP リクエスト由来の文字列 enum を、入力エラーとして明示的に変換する。
 *
 * <p>このクラスは Controller から到達する Request DTO の値だけに使う。内部状態・設定値・
 * DB 値の {@link Enum#valueOf(Class, String)} を置き換えないことで、内部不整合の
 * {@link IllegalArgumentException} が 500 として観測される性質を維持する。</p>
 *
 * <p>{@code null} も未定義値と同じ入力エラーとして扱う。任意項目は呼び出し側で既存の null 分岐を維持し、
 * 値がある場合だけこのメソッドへ渡すため、任意項目の従来挙動は変えない。</p>
 */
public final class EnumInputParser {

    private static final String INVALID_ENUM_MESSAGE = "定義されていない値です";

    private EnumInputParser() {
    }

    /**
     * enum 文字列を変換する。
     *
     * @param enumType 変換先 enum 型
     * @param value HTTP リクエストから受け取った値
     * @param field エラーレスポンスに出すフィールド名
     * @param <E> enum 型
     * @return 変換済み enum
     * @throws BusinessException 未定義値の場合（COMMON_001 / fieldErrors）
     */
    public static <E extends Enum<E>> E parse(Class<E> enumType, String value, String field) {
        if (value == null) {
            throw invalidValue(field);
        }
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException ex) {
            throw invalidValue(field);
        }
    }

    private static BusinessException invalidValue(String field) {
        return new BusinessException(
                CommonErrorCode.COMMON_001,
                List.of(new ErrorResponse.FieldError(field, INVALID_ENUM_MESSAGE)));
    }
}
