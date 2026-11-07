package com.mannschaft.app.organization.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mannschaft.app.common.annotation.TransitionalBridge;
import com.mannschaft.app.common.duplicatename.DuplicateNameNormalizer;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * L1-R 初回ロットの名称正規化と互換入口の契約を検証する。
 *
 * <p>旧入口の入出力は移設前から成立する特性試験。新APIの存在はリフレクションで確認し、
 * 実装前もコンパイルを成功させたうえで契約のアサーションを失敗させる。</p>
 */
@SuppressWarnings("deprecation")
class DuplicateNameNormalizerApiTest {

    private static final String API_CLASS_NAME =
            "com.mannschaft.app.organization.api.DuplicateNameNormalizerApi";
    private static final int LONG_SPACE_COUNT = 100000;

    @ParameterizedTest(name = "{0}")
    @MethodSource("normalizationCases")
    void 旧入口_正規化の契約を保つ(String condition, String input, String expected) {
        assertEquals(expected, DuplicateNameNormalizer.trimSpaces(input), condition);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("normalizationCases")
    void 新API_正規化の契約を保つ(String condition, String input, String expected) {
        Method method = assertDoesNotThrow(() -> apiClass().getDeclaredMethod("trimSpaces", String.class));

        String actual = assertDoesNotThrow(() -> (String) method.invoke(null, input));

        assertEquals(expected, actual, condition);
    }

    @Test
    void DN6_新APIはfinalかつ非公開constructorで公開staticメソッドを一本だけ持つ() {
        assertStaticContract(apiClass());
    }

    @Test
    void DN6_旧入口は公開契約と理由付きの互換橋渡しを維持する() {
        Class<?> legacyClass = DuplicateNameNormalizer.class;

        assertStaticContract(legacyClass);
        assertTrue(legacyClass.isAnnotationPresent(Deprecated.class));
        TransitionalBridge bridge = legacyClass.getAnnotation(TransitionalBridge.class);
        assertNotNull(bridge);
        assertFalse(bridge.reason().isBlank());
        assertFalse(bridge.removalLane().isBlank());
    }

    private static Stream<Arguments> normalizationCases() {
        return Stream.of(
                Arguments.of("DN-1 null", null, ""),
                Arguments.of("DN-1 空文字", "", ""),
                Arguments.of("DN-1 半角スペース一文字", " ", ""),
                Arguments.of("DN-1 半角スペース複数文字", "    ", ""),
                Arguments.of("DN-2 前後のみ除去し内部空白を保持", "  A B  ", "A B"),
                Arguments.of("DN-2 前後空白なし", "A B", "A B"),
                Arguments.of("DN-2 大小文字を保持", "  aB  ", "aB"),
                Arguments.of("DN-3 タブと改行を保持", "\tA\n", "\tA\n"),
                Arguments.of("DN-3 NBSPを保持", "\u00a0A\u00a0", "\u00a0A\u00a0"),
                Arguments.of("DN-3 全角スペースを保持", "\u3000A\u3000", "\u3000A\u3000"),
                Arguments.of("DN-3 制御文字の外の半角スペースだけ除去", " \tA\n ", "\tA\n"),
                Arguments.of("DN-3 NULを保持", "\0A\0", "\0A\0"),
                Arguments.of("DN-3 タブだけの入力も保持", "\t", "\t"),
                Arguments.of("DN-4 非空白一文字", "A", "A"),
                Arguments.of("DN-4 先頭一文字の空白", " A", "A"),
                Arguments.of("DN-4 末尾一文字の空白", "A ", "A"),
                Arguments.of("DN-4 両端一文字の空白", " A ", "A"),
                Arguments.of("DN-4 サロゲートペア", "  \ud83d\ude00  ", "\ud83d\ude00"),
                Arguments.of("DN-5 長い両端空白", " ".repeat(LONG_SPACE_COUNT)
                        + "X" + " ".repeat(LONG_SPACE_COUNT), "X"));
    }

    private static Class<?> apiClass() {
        return assertDoesNotThrow(() -> Class.forName(API_CLASS_NAME), "DN-6 新APIが存在する");
    }

    private static void assertStaticContract(Class<?> type) {
        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertEquals(1, type.getDeclaredConstructors().length);
        assertTrue(Modifier.isPrivate(type.getDeclaredConstructors()[0].getModifiers()));
        assertEquals(0, type.getDeclaredConstructors()[0].getParameterCount());

        Method[] publicMethods = Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toArray(Method[]::new);
        assertEquals(1, publicMethods.length);
        Method method = publicMethods[0];
        assertEquals("trimSpaces", method.getName());
        assertTrue(Modifier.isStatic(method.getModifiers()));
        assertEquals(String.class, method.getReturnType());
        assertArrayEquals(new Class<?>[] {String.class}, method.getParameterTypes());
    }
}
