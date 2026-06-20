package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.BaseEntity;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import jakarta.persistence.Entity;
import lombok.Builder;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * {@code @SuperBuilder} 規約の番人テスト（D-5）: <b>{@code @Entity} が付いており
 * {@link BaseEntity} を継承するクラスは {@code @Builder} を使ってはならない</b>。
 *
 * <p>設計指針: {@code CLAUDE.md}「アーキテクチャ思想 / DB 設計の原則」—
 * 「{@code BaseEntity} 継承 Entity には {@code @Builder(toBuilder=true)} は禁止。
 * {@code toBuilder()} が継承フィールド（{@code id/createdAt/updatedAt}）を引き継がず
 * INSERT 化するバグを防ぐ。{@code @SuperBuilder(toBuilder=true)} を使うこと」。
 *
 * <h2>バグの経緯</h2>
 * <p>{@code @Builder(toBuilder=true)} を {@link BaseEntity} 継承クラスに付与すると、
 * 生成される {@code toBuilder()} は {@link BaseEntity} が宣言する {@code id},
 * {@code createdAt}, {@code updatedAt} を引き継がない。その結果、既存エンティティを
 * {@code toBuilder().xxx(v).build()} で更新しようとすると {@code id=null} の新オブジェクト
 * が生成され、{@code save()} が UPDATE ではなく INSERT を発行してしまう（一意制約違反 or
 * 行重複）。このバグは純粋な単体テストでは検出が困難で、実機 E2E や統合テストで初めて
 * 露見するため、全 657 エンティティを {@code @SuperBuilder(toBuilder=true)} へ移行する
 * 大規模リファクタリング（toBuilder 更新破壊 systemic 根治キャンペーン）を実施した。
 *
 * <h2>恒久ルール方式</h2>
 * <p>今回の移行により違反は <b>0 件</b> になったため、
 * {@link com.tngtech.archunit.library.freeze.FreezingArchRule} は使用しない。
 * クラスレベルの {@link Builder} アノテーションを検出した瞬間に fail させる恒久ルールとして
 * 定義する。フィールドレベルの {@code @Builder.Default} はクラスレベルの {@link Builder}
 * とは異なるアノテーションのため、本ルールには引っかからない（誤検知なし）。
 *
 * <h2>ルール ID</h2>
 * <p>D-5（他のアーキテクチャ番人テストとの対応）
 * <ul>
 *   <li>D-1: {@link CrossDomainEntityImportArchTest} — クロスドメイン Entity 依存禁止</li>
 *   <li>D-2b: {@link EntityUuidV7ConventionArchTest} — 新規 Entity は UUIDv7</li>
 *   <li>D-4: {@link CrossDomainForeignKeyArchTest} — クロスドメイン FK 禁止</li>
 *   <li>D-5: 本テスト — BaseEntity 継承 Entity への {@code @Builder} 禁止</li>
 * </ul>
 */
@AnalyzeClasses(
    packages = "com.mannschaft.app",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class EntitySuperBuilderConventionArchTest {

    @ArchTest
    static final ArchRule entities_extending_base_entity_must_not_use_builder =
        classes().that()
            .areAnnotatedWith(Entity.class)
            .and().areAssignableTo(BaseEntity.class)
            .should().notBeAnnotatedWith(Builder.class)
            .because("BaseEntity継承Entityには@Builder(toBuilder=true)は禁止。"
                + "toBuilder()が継承フィールド(id/createdAt/updatedAt)を引き継がずINSERT化するバグを防ぐ。"
                + "@SuperBuilder(toBuilder=true)を使うこと（CLAUDE.mdアーキテクチャ思想参照）")
            .as("entities extending BaseEntity must not use @Builder (D-5)");
}
