package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STRING 永続化enumの定数追加を検知し、二段階展開のレビューを要求する。
 * 台帳は違反免除ではなく読取り互換性の確認基準。デプロイ済みの証明には使わない。
 * AttributeConverter・native SQL・JSON内部のenumは対象外。
 * 全本番classの注釈を保守的に収集するため、未使用のマッピング宣言も対象となる。
 */
@AnalyzeClasses(packages = "com.mannschaft.app", importOptions = ImportOption.DoNotIncludeTests.class)
@ArchTag(ArchUnitTestTag.ARCHUNIT)
class PersistedEnumCompatibilityArchTest {
    @ArchTest
    static void 永続化enumの定数は互換性台帳と一致する(JavaClasses classes) throws IOException {
        Set<String> actual = PersistedEnumInventory.constants(classes);
        assertThat(actual).as("本番enum走査が空振りしていないこと").isNotEmpty();
        Path snapshot = Path.of("src/test/resources/persisted_enum_guard/string_enum_compatibility.txt");
        Set<String> expected;
        try (var lines = Files.lines(snapshot, StandardCharsets.UTF_8)) {
            expected = lines.map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
        Set<String> added = new TreeSet<>(actual);
        added.removeAll(expected);
        Set<String> removed = new TreeSet<>(expected);
        removed.removeAll(actual);
        assertThat(added).withFailMessage("永続化enum追加を検知。docs/development/persisted_enum_deployment.mdに従い、"
                + "定数だけを配る第1段階と書込み開始の第2段階をレビューしてから互換性台帳を更新する。"
                + "\n追加定数（%s件）:\n%s", added.size(), String.join("\n", added))
                .isEmpty();
        assertThat(removed).withFailMessage("削除・改名もDB残存値の読取りとrollbackを確認し、"
                + "互換性台帳を同じPRで更新する。\n削除定数（%s件）:\n%s", removed.size(), String.join("\n", removed))
                .isEmpty();
    }
}
