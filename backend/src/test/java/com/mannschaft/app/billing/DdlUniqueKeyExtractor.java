package com.mannschaft.app.billing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * PR6b-1 残務①: {@code billing} ドメインの migration SQL から {@code UNIQUE KEY} 制約
 * （{@code CREATE TABLE} 内の宣言と、後続 migration の {@code ALTER TABLE ... ADD UNIQUE KEY} の
 * 両方）を実読して抽出する共通ヘルパ。
 *
 * <p><b>なぜ SQL ファイルを実読するのか</b>: {@code test} profile の schema は Entity 由来の
 * DDL 自動生成（{@code ddl-auto=create}）で構築され、V196 等の migration 自体は再現されない
 * （{@code feedback_test_profile_ddl_create_skips_flyway_seed} 前例）。したがって Entity と DDL の
 * UNIQUE KEY の整合は、DB へ実際に投入して確認する経路が存在せず、migration ファイルのテキストを
 * 直接読んで照合するほかない。</p>
 *
 * <p>この抽出は「テーブル定義が複数 migration に分散している」ケース（例:
 * {@code billing_contracts} は V150 で CREATE、V151/V198 で ADD UNIQUE KEY）を正しく合算する。
 * DROP KEY / DROP INDEX による撤回は billing ドメインの対象テーブルには存在しない
 * （2026-09-17 時点で実走確認済み）ため、この抽出器は撤回を扱わない。将来 DROP が現れた場合は
 * この前提が崩れるため、抽出器の拡張が必要になる。</p>
 */
final class DdlUniqueKeyExtractor {

    private static final Pattern STATEMENT_SPLIT = Pattern.compile(";");

    private static final Pattern CREATE_TABLE_UNIQUE = Pattern.compile(
            "UNIQUE\\s+KEY\\s+(\\w+)\\s*\\(([^)]+)\\)");

    private DdlUniqueKeyExtractor() {
    }

    private static String readAll(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("migration ファイルが読めない: " + path, e);
        }
    }

    /**
     * 与えた migration ファイル群（記述順で読む必要はない。CREATE/ALTER は独立した文として扱う）
     * から、指定テーブルの UNIQUE KEY 制約を {@code 制約名 -> 正規化済みカラム名集合} で返す。
     */
    static Map<String, TreeSet<String>> extractUniqueKeys(Stream<Path> migrationFiles, String tableName) {
        Map<String, TreeSet<String>> result = new LinkedHashMap<>();
        String qualifiedTable = "\\`?" + Pattern.quote(tableName) + "\\`?";
        Pattern createTable = Pattern.compile(
                "CREATE\\s+TABLE\\s+" + qualifiedTable + "\\s*\\(", Pattern.CASE_INSENSITIVE);
        Pattern alterTable = Pattern.compile(
                "ALTER\\s+TABLE\\s+" + qualifiedTable + "\\b", Pattern.CASE_INSENSITIVE);

        migrationFiles.forEach(path -> {
            String sql = readAll(path);
            for (String statement : STATEMENT_SPLIT.split(sql)) {
                boolean isCreate = createTable.matcher(statement).find();
                boolean isAlter = alterTable.matcher(statement).find();
                if (!isCreate && !isAlter) {
                    continue;
                }
                Matcher m = CREATE_TABLE_UNIQUE.matcher(statement);
                while (m.find()) {
                    String name = m.group(1);
                    TreeSet<String> columns = Stream.of(m.group(2).split(","))
                            .map(String::trim)
                            .map(s -> s.replace("`", ""))
                            .collect(Collectors.toCollection(TreeSet::new));
                    result.put(name, columns);
                }
            }
        });
        return result;
    }
}
