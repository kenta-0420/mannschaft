package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP Request DTO の getter を {@code Enum.valueOf} へ直接渡す経路を防ぐ番人。
 *
 * <p>Request DTO は Controller から Service へ {@code req}/{@code request} として渡される
 * 既存の命名規約を持つ。この構文上明白な形だけを対象にし、内部状態・DB 値・設定値を変換する
 * {@code valueOf} は対象外とする。</p>
 *
 * <p>record accessor、変数経由、文字列の事前変形（例: {@code toUpperCase()}）はこの番人の
 * 変数名・構文依存の射程外である。CMP-108 ではそれらを別途全件走査し、HTTP 入力由来と確認した
 * ものだけを {@code EnumInputParser} へ移行する。</p>
 *
 */
@DisplayName("HTTP入力enumの直接valueOf番人")
class HttpRequestEnumValueOfGuardTest {

    private static final Path MAIN_SOURCE_ROOT = Path.of("src/main/java");
    private static final Pattern RAW_REQUEST_ENUM_VALUE_OF = Pattern.compile(
            "\\b[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*"
                    + "\\.valueOf\\(\\s*(?:req|request)\\.get[A-Z][A-Za-z0-9_]*\\(\\)\\s*\\)");

    @Test
    @DisplayName("HTTP入力getter直結のenum valueOfは本番ソースに残らない")
    void HTTP入力getter直結のenumValueOfは本番ソースに残らない() throws IOException {
        List<Violation> violations = collectViolations(MAIN_SOURCE_ROOT);
        assertThat(violations)
                .as("EnumInputParser.parseへ移行する違反: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("番人は違反となるHTTP入力getter直結valueOfを検出する")
    void 番人は違反となるHTTP入力getter直結valueOfを検出する() {
        assertThat(collectViolations("""
                class Sample {
                    void update(Request request) {
                        CommentOption.valueOf(request.getCommentOption());
                    }
                }
                """, "Sample.java"))
                .singleElement()
                .satisfies(violation -> assertThat(violation.line()).isEqualTo(4));
    }

    @Test
    @DisplayName("番人は共通parser経由のHTTP入力を許可する")
    void 番人は共通parser経由のHTTP入力を許可する() {
        assertThat(collectViolations("""
                class Sample {
                    void update(Request request) {
                        EnumInputParser.parse(CommentOption.class, request.getCommentOption(), "commentOption");
                    }
                }
                """, "Sample.java"))
                .isEmpty();
    }

    static List<Violation> collectViolations(Path sourceRoot) throws IOException {
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .flatMap(HttpRequestEnumValueOfGuardTest::violationsInFile)
                    .toList();
        }
    }

    static List<Violation> collectViolations(String source, String fileName) {
        Matcher matcher = RAW_REQUEST_ENUM_VALUE_OF.matcher(source);
        java.util.ArrayList<Violation> violations = new java.util.ArrayList<>();
        while (matcher.find()) {
            int line = (int) source.substring(0, matcher.start()).lines().count() + 1;
            violations.add(new Violation(fileName, line, matcher.group()));
        }
        return violations;
    }

    private static Stream<Violation> violationsInFile(Path path) {
        try {
            return collectViolations(Files.readString(path), path.toString()).stream();
        } catch (IOException ex) {
            throw new IllegalStateException("本番ソースを読めません: " + path, ex);
        }
    }

    record Violation(String file, int line, String source) {
    }
}
