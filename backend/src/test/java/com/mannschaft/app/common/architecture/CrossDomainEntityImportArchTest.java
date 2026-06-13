package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * モジュラーモノリス境界の番人テスト（D-1）: <b>あるドメインのクラスが別ドメインの
 * {@code entity} パッケージを参照してはならない</b>。
 *
 * <p>設計指針: {@code CLAUDE.md}「アーキテクチャ思想 / ドメイン境界の原則」
 * — 「異なるドメインの Entity を直接参照しない（ID のみ保持する）。ドメイン間の
 * データ取得は Service のメソッド呼び出し経由で行う」。
 *
 * <h2>ルールの定義</h2>
 * <p>パッケージ {@code com.mannschaft.app.<domain>} の先頭セグメント {@code <domain>}
 * をそのクラスの所属ドメインとみなす。クラス X（ドメイン {@code dX}）が
 * クラス Y（ドメイン {@code dY}、かつ Y が {@code ..entity..} 配下）に依存する場合、
 * {@code dX != dY} ならば違反とする。
 *
 * <h2>除外</h2>
 * <ul>
 *   <li><b>enum は対象外</b>: {@code ..entity.enums..} 配下（例
 *       {@code village.entity.enums.*}）への参照は、共有される値オブジェクト的な
 *       性質を持つため許容する。</li>
 *   <li><b>{@code common} ドメイン</b>: {@code common.entity.UuidV7Entity} 等の
 *       共有基盤は全ドメインから参照されるため、{@code common} を相手先ドメインと
 *       する依存は常に許容する。また {@code common} 配下のクラス自身も発生元として
 *       対象外とする。</li>
 *   <li>自ドメイン内の {@code entity} 参照は当然ながら許容。</li>
 * </ul>
 *
 * <h2>凍結方式（FreezingArchRule）</h2>
 * <p>本ルールは既に大量の既存違反を抱えているため、{@link FreezingArchRule} で
 * 既存違反を凍結ストア（{@code src/test/resources/archunit_store/}）に記録し、
 * <b>新規違反のみ</b> fail させる。凍結ストアの中身は git 管理対象であり、
 * これが「凍結の本体」である（{@code archunit.properties} 参照）。
 *
 * <p>既存違反を 1 件解消すると {@code freeze.refreeze=true} によりストアが自動縮小
 * される（chip-away 運用）。新たにクロスドメイン Entity 参照を追加した瞬間に
 * 本テストが fail し、境界侵犯を機械的に検知する。
 */
@AnalyzeClasses(
    packages = "com.mannschaft.app",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class CrossDomainEntityImportArchTest {

    /** アプリのルートパッケージ。ドメイン名抽出の基点。 */
    private static final String ROOT_PACKAGE = "com.mannschaft.app";

    /**
     * 全ドメインから共有される基盤パッケージ。相手先ドメインがこれの場合は許容し、
     * 発生元がこれの場合も対象外とする。
     */
    private static final String SHARED_DOMAIN = "common";

    @ArchTest
    static final ArchRule no_cross_domain_entity_dependency =
        FreezingArchRule.freeze(
            classes().that().resideInAPackage("com.mannschaft.app..")
                .should(notDependOnOtherDomainEntities())
                .because("CLAUDE.md ドメイン境界の原則 — 異なるドメインの Entity を"
                    + "直接参照してはならない（ID のみ保持し、データ取得は Service 経由）。"
                    + "enum（..entity.enums..）と common 基盤は除外。"
                    + "既存違反は凍結し、新規違反のみ fail させる"));

    // -----------------------------------------------------------------------
    // ヘルパー
    // -----------------------------------------------------------------------

    /**
     * 「クラスが別ドメインの {@code entity} パッケージに依存していないこと」を検査する
     * {@link ArchCondition}。違反した依存ごとに 1 件の event を報告する
     * （FreezingArchRule が個別 event 単位で凍結するため、event は安定した文言にする）。
     */
    private static ArchCondition<JavaClass> notDependOnOtherDomainEntities() {
        return new ArchCondition<>("not depend on other domain entities") {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                String sourceDomain = domainOf(clazz.getPackageName());
                if (sourceDomain == null || SHARED_DOMAIN.equals(sourceDomain)) {
                    // ドメイン外（com.mannschaft.app 直下等）や共有基盤は対象外
                    return;
                }
                for (Dependency dep : clazz.getDirectDependenciesFromSelf()) {
                    JavaClass target = dep.getTargetClass();
                    String targetPkg = target.getPackageName();
                    if (!isEntityPackage(targetPkg)) {
                        continue;
                    }
                    if (isEnumPackage(targetPkg)) {
                        // enum パッケージは共有を許容
                        continue;
                    }
                    String targetDomain = domainOf(targetPkg);
                    if (targetDomain == null
                            || SHARED_DOMAIN.equals(targetDomain)
                            || targetDomain.equals(sourceDomain)) {
                        // common 基盤への依存・自ドメイン内参照は許容
                        continue;
                    }
                    String message = String.format(
                        "%s (domain '%s') depends on other-domain entity %s (domain '%s')",
                        clazz.getName(), sourceDomain,
                        target.getName(), targetDomain);
                    events.add(SimpleConditionEvent.violated(dep, message));
                }
            }
        };
    }

    /**
     * パッケージ名から所属ドメイン（{@code com.mannschaft.app} 直下の先頭セグメント）を
     * 取り出す。アプリ配下でない場合は {@code null}。
     */
    private static String domainOf(String packageName) {
        if (packageName == null || !packageName.startsWith(ROOT_PACKAGE)) {
            return null;
        }
        String rest = packageName.substring(ROOT_PACKAGE.length());
        if (rest.startsWith(".")) {
            rest = rest.substring(1);
        }
        if (rest.isEmpty()) {
            return null;
        }
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    /** {@code ..entity} または {@code ..entity.*} 配下かどうか。 */
    private static boolean isEntityPackage(String packageName) {
        return packageName.contains(".entity.") || packageName.endsWith(".entity");
    }

    /** {@code ..entity.enums} または {@code ..entity.enums.*} 配下かどうか。 */
    private static boolean isEnumPackage(String packageName) {
        return packageName.contains(".entity.enums.") || packageName.endsWith(".entity.enums");
    }
}
