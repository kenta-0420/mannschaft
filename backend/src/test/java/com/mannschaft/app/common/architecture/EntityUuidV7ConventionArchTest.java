package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

import jakarta.persistence.Entity;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * 新規 Entity の主キー規約の番人テスト（D-2b）: <b>{@code @Entity} が付いたクラスは
 * {@link UuidV7Entity} を継承すべき</b>。
 *
 * <p>設計指針: {@code CLAUDE.md}「DB 設計の原則 #6」— 新規に作成するテーブルの
 * Entity は {@link UuidV7Entity} を継承し、主キーを UUIDv7 にすること。
 * BIGINT AUTO_INCREMENT は単一の発番サーバーが必要でシャーディングできないため、
 * 新規テーブルから先行して UUIDv7 へ移行する方針。
 *
 * <h2>凍結方式（FreezingArchRule）</h2>
 * <p>既存テーブルの BIGINT Entity が多数存在し、原則 #6 は「新規テーブルのみ」を
 * 対象とする（既存テーブルの ID 型変更は破壊的変更につき禁止）。そのため
 * {@link FreezingArchRule} で既存の BIGINT Entity を凍結ストア
 * （{@code src/test/resources/archunit_store/}）に記録し、<b>新規に追加された
 * {@code @Entity} のみ</b> fail させる。
 *
 * <p>これにより、新しい Entity クラスを追加した瞬間に「{@link UuidV7Entity} を
 * 継承していない」ことを機械的に検知できる。既存 Entity を UUIDv7 へ移行すると
 * {@code freeze.refreeze=true} によりストアが自動縮小される（{@code archunit.properties}）。
 *
 * <p>{@link CrossDomainEntityImportArchTest} と同一の凍結ストアディレクトリを共用する。
 */
@AnalyzeClasses(
    packages = "com.mannschaft.app",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class EntityUuidV7ConventionArchTest {

    @ArchTest
    static final ArchRule entities_should_extend_uuid_v7_entity =
        FreezingArchRule.freeze(
            classes().that().areAnnotatedWith(Entity.class)
                .should().beAssignableTo(UuidV7Entity.class)
                .because("CLAUDE.md DB 設計の原則 #6 — 新規 Entity は UuidV7Entity を"
                    + "継承し主キーを UUIDv7 にすること。既存 BIGINT Entity は凍結し、"
                    + "新規追加された @Entity のみ fail させる")
                // 凍結ストアの照合キー（rule description）を固定し、僅かな文言差で
                // 別ルール扱い＝新規ストア生成（＝新規違反の取りこぼし）になるのを防ぐ。
                .as("entities should extend UuidV7Entity (D-2b)"));
}
