package com.mannschaft.app.ranch.service;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UCharacterCategory;
import com.ibm.icu.lang.UProperty;
import com.ibm.icu.text.BreakIterator;
import com.ibm.icu.text.Normalizer2;
import com.ibm.icu.util.ULocale;
import java.nio.charset.StandardCharsets;

/** 孵化時の永久名を Unicode 17 / UAX29 rev47 の extended grapheme で検査する。 */
public class DinosaurNameValidator {
    private static final int MAX_GRAPHEMES = 10;
    private static final int MAX_CODE_POINTS = 160;
    private static final int MAX_UTF8_BYTES = 512;
    private static final Normalizer2 NFC = Normalizer2.getNFCInstance();

    public String normalize(String input) {
        if (input == null) {
            throw invalidName();
        }
        // trim で制御文字を隠さず、未対応 surrogate の UTF-8 置換保存も拒否する。
        input.codePoints().forEach(codePoint -> {
            int type = UCharacter.getType(codePoint);
            if (type == UCharacterCategory.CONTROL || UCharacter.hasBinaryProperty(codePoint, UProperty.BIDI_CONTROL)
                    || type == UCharacterCategory.LINE_SEPARATOR
                    || type == UCharacterCategory.PARAGRAPH_SEPARATOR || type == UCharacterCategory.SURROGATE) {
                throw invalidName();
            }
        });
        String name = NFC.normalize(trimUnicodeWhitespace(input));
        if (name.isEmpty() || name.codePointCount(0, name.length()) > MAX_CODE_POINTS
                || name.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES
                || name.codePoints().noneMatch(DinosaurNameValidator::isVisibleCodePoint)) {
            throw invalidName();
        }
        // BreakIterator は可変なので共有しない。ROOT と ICU4J 78.3 で版を固定する。
        BreakIterator characters = BreakIterator.getCharacterInstance(ULocale.ROOT);
        characters.setText(name);
        characters.first();
        int graphemes = 0;
        while (characters.next() != BreakIterator.DONE) {
            if (++graphemes > MAX_GRAPHEMES) {
                throw invalidName();
            }
        }
        return name;
    }

    private static String trimUnicodeWhitespace(String input) {
        int start = 0;
        int end = input.length();
        while (start < end && UCharacter.isUWhiteSpace(input.codePointAt(start))) {
            start += Character.charCount(input.codePointAt(start));
        }
        while (end > start && UCharacter.isUWhiteSpace(input.codePointBefore(end))) {
            end -= Character.charCount(input.codePointBefore(end));
        }
        return input.substring(start, end);
    }

    private static boolean isVisibleCodePoint(int codePoint) {
        return !UCharacter.isUWhiteSpace(codePoint)
                && UCharacter.getType(codePoint) != UCharacterCategory.FORMAT
                && !UCharacter.hasBinaryProperty(codePoint, UProperty.DEFAULT_IGNORABLE_CODE_POINT);
    }

    private static IllegalArgumentException invalidName() {
        // 名前を例外へ埋め込まず、共通ログにも原入力を残さない。
        return new IllegalArgumentException("恐竜の名前が不正です");
    }
}
