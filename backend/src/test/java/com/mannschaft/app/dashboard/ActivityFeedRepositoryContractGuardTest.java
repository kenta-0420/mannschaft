package com.mannschaft.app.dashboard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F03.18 第三隊 — {@code ActivityFeedRepository} の契約を機械検証する番人テスト。
 *
 * <p>検証する軸は2つ:</p>
 * <ul>
 *   <li><b>AC-16</b>: Repository に「独自の閲覧述語」を書かせない。
 *       可視性の正準は {@code AbstractContentVisibilityResolver#filterAccessible} 一本であり、
 *       {@code visibility} / {@code min_view_role} を含む JPQL・SQL を Repository に増やすと
 *       漏洩源が二重化する（memory: feedback_visibility_bypass_f00_audit）。</li>
 *   <li><b>AC-17</b>: 3本のクエリメソッドの {@code ORDER BY} が {@code a.id DESC} に揃っていること。
 *       カーソル条件が {@code a.id < :cursor} である以上、整列キーが {@code createdAt} だと
 *       ページ境界で行の重複・欠落が起こる。</li>
 * </ul>
 *
 * <p>本テストは「検体の列挙」ではなく「判定の軸の列挙」である（memory:
 * feedback_detector_axes_not_specimens）。対象は {@code ActivityFeedRepository} 1ファイルに
 * 限定し、範囲外（他 Repository の閲覧述語）は本テストの穴ではなく意図的な線引きである。</p>
 */
@DisplayName("ActivityFeedRepository 契約番人（F03.18 AC-16 / AC-17）")
class ActivityFeedRepositoryContractGuardTest {

    /** 検証対象ファイル。 */
    private static final String TARGET =
            "src/main/java/com/mannschaft/app/dashboard/repository/ActivityFeedRepository.java";

    /**
     * ORDER BY を持つべき 3 本のクエリメソッド名。
     * 設計書 F03.18 §4.2「裁定」で id 順への統一が明記されている 3 本と完全一致させる。
     */
    private static final List<String> ORDERED_QUERY_METHODS = List.of(
            "findByScopeAndExcludeActor",
            "findByScopeAndExcludeActorWithCursor",
            "findByScopesAndExcludeActor");

    /** 閲覧述語の禁止語（大文字小文字・スネーク/キャメル両方を拾う）。 */
    private static final List<Pattern> FORBIDDEN_VISIBILITY_PREDICATES = List.of(
            Pattern.compile("\\bvisibility\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("min_view_role", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bminViewRole\\b"));

    /** {@code @Query("...")} の中身（連結された文字列リテラル群）を抜き出す正規表現。 */
    private static final Pattern QUERY_ANNOTATION =
            Pattern.compile("@Query\\s*\\(\\s*((?:\"(?:[^\"\\\\]|\\\\.)*\"\\s*\\+?\\s*)+)",
                    Pattern.DOTALL);

    private static String readTarget() {
        Path path = resolveBackendRoot().resolve(TARGET);
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("検証対象を読めない: " + path, e);
        }
    }

    /**
     * テスト実行時の作業ディレクトリが {@code backend/} でもリポジトリルートでも動くように解決する。
     */
    private static Path resolveBackendRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        if (Files.exists(cwd.resolve(TARGET))) {
            return cwd;
        }
        Path backend = cwd.resolve("backend");
        if (Files.exists(backend.resolve(TARGET))) {
            return backend;
        }
        throw new IllegalStateException("ActivityFeedRepository.java を解決できない。cwd=" + cwd);
    }

    /**
     * {@code @Query} アノテーションの中身をすべて取り出し、文字列リテラルを結合して返す。
     */
    private static List<String> extractQueryBodies(String source) {
        List<String> bodies = new ArrayList<>();
        Matcher m = QUERY_ANNOTATION.matcher(source);
        while (m.find()) {
            String raw = m.group(1);
            StringBuilder sb = new StringBuilder();
            Matcher lit = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(raw);
            while (lit.find()) {
                sb.append(lit.group(1));
            }
            bodies.add(sb.toString());
        }
        return bodies;
    }

    @Test
    @DisplayName("AC-16: Repository に visibility / min_view_role を含む独自の閲覧述語が無い")
    void ac16_noOwnVisibilityPredicate() {
        List<String> bodies = extractQueryBodies(readTarget());

        // 番人自身の生存証明: @Query を1本も拾えていないなら「0件だから合格」という
        // 最も危険な緑になる（memory: feedback_sql_count_guard_never_ran...）。
        assertThat(bodies)
                .as("@Query を1本も抽出できていない。番人が機能していない疑い")
                .isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (String body : bodies) {
            for (Pattern forbidden : FORBIDDEN_VISIBILITY_PREDICATES) {
                if (forbidden.matcher(body).find()) {
                    violations.add("[" + forbidden.pattern() + "] " + body);
                }
            }
        }

        assertThat(violations)
                .as("ActivityFeedRepository に独自の閲覧述語を書いてはならない。"
                        + "可視性は ScheduleVisibilityResolver.filterAccessible に一元化する")
                .isEmpty();
    }

    @Test
    @DisplayName("AC-17: 3本のクエリメソッドの ORDER BY がすべて a.id DESC")
    void ac17_allOrderByIdDesc() {
        String source = readTarget();

        for (String method : ORDERED_QUERY_METHODS) {
            int methodIdx = source.indexOf(" " + method + "(");
            assertThat(methodIdx)
                    .as("メソッド %s が ActivityFeedRepository に存在しない（番人の前提崩れ）", method)
                    .isGreaterThan(0);

            // 当該メソッド宣言の直前にある @Query の中身を取る。
            String beforeMethod = source.substring(0, methodIdx);
            int queryIdx = beforeMethod.lastIndexOf("@Query");
            assertThat(queryIdx)
                    .as("メソッド %s に @Query が付いていない", method)
                    .isGreaterThan(0);

            List<String> bodies = extractQueryBodies(source.substring(queryIdx));
            assertThat(bodies).as("%s の @Query 本文を抽出できない", method).isNotEmpty();
            String body = bodies.get(0);

            assertThat(body)
                    .as("%s の ORDER BY は a.id DESC でなければならない（カーソル条件 a.id < :cursor と"
                            + "整列キーを一致させ、ページ境界の重複・欠落を根治する）。実際の本文: %s",
                            method, body)
                    .contains("ORDER BY a.id DESC");
            assertThat(body)
                    .as("%s に createdAt 整列が残っている: %s", method, body)
                    .doesNotContain("ORDER BY a.createdAt");
        }
    }

    @Test
    @DisplayName("番人の自己検証: 禁止語パターンは実際に閲覧述語を検出できる")
    void guardItself_detectsForbiddenPredicate() {
        // 「最初から green の番人は守っていない、測っていない」ことへの対策。
        // 意図的に汚染した検体で検出が発火することを確かめる。
        String polluted = "SELECT a FROM ActivityFeedEntity a WHERE a.visibility = 'PUBLIC'";
        boolean detected = FORBIDDEN_VISIBILITY_PREDICATES.stream()
                .anyMatch(p -> p.matcher(polluted).find());
        assertThat(detected).as("禁止語パターンが閲覧述語を検出できていない").isTrue();

        String pollutedSnake = "AND s.min_view_role <= :role";
        boolean detectedSnake = FORBIDDEN_VISIBILITY_PREDICATES.stream()
                .anyMatch(p -> p.matcher(pollutedSnake).find());
        assertThat(detectedSnake).as("min_view_role を検出できていない").isTrue();
    }
}
