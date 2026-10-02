package com.mannschaft.app.common.architecture;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Flyway migration ファイル命名の番人テスト（{@code backend/.claudecode.md} §18）。
 *
 * <h2>背景（2026-07-07 V141.001 二重事故）</h2>
 * <p>{@code CLAUDE.md} / {@code backend/.claudecode.md} §18 は「新規 migration の major は
 * origin/main 全体の最大 major + 1、minor はタイムスタンプ（{@code yyyyMMddHHmmss}）を使う」と
 * 明文化されていたが、規約だけでは守られず V137〜V143 系列で連番 minor（{@code .001} 等）が
 * 積み上がり、ついに並行 PR が同時に {@code V141.001} を名乗って main へ merge され、Flyway が
 * 「同一バージョンの migration が複数存在する」で fresh 環境の起動に失敗する障害が発生した。
 * 規約の周知だけでは再発するため、本テストで<b>ビルド時に機械的に拒否</b>する。</p>
 *
 * <h2>検査内容</h2>
 * <ul>
 *   <li><b>命名フォーマット</b>: {@link FlywayLegacyMigrationBaseline#FROZEN_FILENAMES}
 *       （凍結時点で実在した既存ファイル）に載っていない新規ファイルは、
 *       {@code V<major>.<14桁タイムスタンプ>__<説明>.sql} の形式でなければ fail する。
 *       連番 minor（{@code V999.001__...}）や旧来の異形はこの正規表現に一致しないため、
 *       凍結リスト外に新規追加された瞬間に検知される。</li>
 *   <li><b>バージョン重複検知</b>: 凍結・新規を問わず全ファイルを対象に、Flyway が実際に使う
 *       {@link MigrationVersion} の数値正規化（先頭ゼロ無視・{@code 141.001} と
 *       {@code 141.1} を同一視 等）でバージョン文字列を比較し、同一バージョンを名乗る
 *       ファイルが 2 件以上あれば fail する。V141.001 二重事故そのものへの直接的な番人。</li>
 * </ul>
 *
 * <p>{@code rollback/} 配下のロールバック専用 SQL は Flyway の適用対象外のため走査しない
 * （{@code db/migration} 直下のみを非再帰で走査）。</p>
 */
class FlywayTimestampNamingGuardTest {

    /** マイグレーション SQL のルート（worktree からの相対パス）。rollback/ 等のサブディレクトリは含めない。 */
    private static final Path MIGRATION_DIR =
        Paths.get("src", "main", "resources", "db", "migration");

    /**
     * 凍結リスト外の新規ファイルに強制するタイムスタンプ minor 形式。
     * {@code V<major:数字1桁以上>.<minor:14桁タイムスタンプ>__<説明:小文字英数字とアンダースコア>.sql}
     */
    private static final Pattern TIMESTAMP_MINOR_PATTERN =
        Pattern.compile("^V\\d+\\.\\d{14}__[a-z0-9_]+\\.sql$");

    /** ファイル名からバージョン文字列（先頭 V を除き {@code __} の直前まで）を取り出す。 */
    private static final Pattern VERSION_PREFIX = Pattern.compile("^V([0-9.]+)__");

    @Test
    @DisplayName("凍結リスト外の新規migrationは連番minorではなくタイムスタンプminor形式を強制される")
    void 凍結リスト外の新規ファイルはタイムスタンプminor形式を満たす() throws IOException {
        List<String> versionedSqlFiles = listVersionedMigrationFiles();

        List<String> violations = new ArrayList<>();
        for (String fileName : versionedSqlFiles) {
            if (FlywayLegacyMigrationBaseline.FROZEN_FILENAMES.contains(fileName)) {
                continue; // 凍結免除（既存資産。リネームしない）
            }
            if (!TIMESTAMP_MINOR_PATTERN.matcher(fileName).matches()) {
                violations.add(fileName);
            }
        }

        if (violations.isEmpty()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("凍結リスト（既存資産）に無い新規 Flyway migration が、"
            + "タイムスタンプ minor 形式（V{major}.{yyyyMMddHHmmss}__{説明}.sql）に"
            + "従っていません。連番minor（例: V999.001__...）は禁止です"
            + "（backend/.claudecode.md §18 / V141.001 二重事故の再発防止）。\n"
            + "`date -u '+%Y%m%d%H%M%S'` でタイムスタンプを採り、ファイル名を"
            + "V{major}.{タイムスタンプ}__{説明}.sql に付け替えてください。\n違反ファイル:\n");
        for (String v : violations) {
            sb.append("  ✗ ").append(v).append('\n');
        }
        fail(sb.toString());
    }

    @Test
    @DisplayName("同一Flywayバージョンを名乗るmigrationファイルが複数存在しないこと（V141.001二重事故の直接番人）")
    void バージョン文字列の重複が存在しない() throws IOException {
        List<String> versionedSqlFiles = listVersionedMigrationFiles();

        // MigrationVersion は Flyway が実際に使う数値正規化（先頭ゼロ無視・成分ごとの数値比較）で
        // 同一性を判定する。単純な文字列一致では "V141.001" と "V141.1" のような表記ゆれの
        // 実質的な重複を見逃すため、Flyway 本体と同じ比較ロジックを使う。
        Map<MigrationVersion, List<String>> byVersion = new LinkedHashMap<>();
        for (String fileName : versionedSqlFiles) {
            Matcher m = VERSION_PREFIX.matcher(fileName);
            if (!m.find()) {
                continue; // V<数字>__ 形式でない異形（本テストの対象外）
            }
            MigrationVersion version = MigrationVersion.fromVersion(m.group(1));
            byVersion.computeIfAbsent(version, v -> new ArrayList<>()).add(fileName);
        }

        List<Map.Entry<MigrationVersion, List<String>>> duplicates = byVersion.entrySet().stream()
            .filter(e -> e.getValue().size() > 1)
            .toList();

        if (duplicates.isEmpty()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("同一 Flyway バージョンを名乗る migration ファイルが複数存在します。"
            + "Flyway は fresh 環境で「同一バージョンの migration が複数存在する」で起動に失敗します"
            + "（2026-07-07 V141.001 二重事故と同型の障害）。いずれかをタイムスタンプ minor で"
            + "別バージョンに採番し直してください:\n");
        for (Map.Entry<MigrationVersion, List<String>> e : duplicates) {
            sb.append("  ✗✗ バージョン ").append(e.getKey()).append(" : ")
                .append(String.join(", ", e.getValue())).append('\n');
        }
        fail(sb.toString());
    }

    /** {@code db/migration} 直下（非再帰）の {@code V*.sql} ファイル名一覧を返す。 */
    private static List<String> listVersionedMigrationFiles() throws IOException {
        assertTrue(Files.isDirectory(MIGRATION_DIR),
            "マイグレーションディレクトリが見つからない: " + MIGRATION_DIR.toAbsolutePath()
                + "（CWD=" + Paths.get("").toAbsolutePath() + "）");

        List<String> names = new ArrayList<>();
        try (Stream<Path> stream = Files.list(MIGRATION_DIR)) {
            stream
                .filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .filter(name -> name.length() > 0 && (name.charAt(0) == 'V' || name.charAt(0) == 'v'))
                .filter(name -> name.toLowerCase().endsWith(".sql"))
                .forEach(names::add);
        }
        return names;
    }
}
