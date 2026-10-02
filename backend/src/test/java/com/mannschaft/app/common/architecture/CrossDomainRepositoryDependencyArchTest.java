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
 * クロスドメイン Repository 依存の番人テスト（D-5）: <b>あるドメインのクラスは、別ドメインの
 * {@code repository} パッケージに直接依存してはならない</b>。
 *
 * <p>設計指針: {@code CLAUDE.md}「アーキテクチャ思想 / ドメイン境界の原則」—
 * 「ドメイン間のデータ取得は Service のメソッド呼び出し経由で行う」。別ドメインの Repository を
 * 直接 DI して DB を横断すると、将来の水平シャーディングでドメインごとのデータストア分割が
 * できなくなる。{@link CrossDomainTransactionalArchTest}（D-3）が
 * <b>{@code @Transactional} 付きクラスに限定</b>して越境 Repository 依存を検知するのに対し、
 * 本 D-5 は<b>{@code @Transactional} の有無を問わず全クラス</b>を対象に一般化した番人である
 * （Service 以外の Facade/Resolver/Batch/Listener 等からの越境 Repository 依存も捕捉する）。
 *
 * <h2>検査対象</h2>
 * <p>{@code com.mannschaft.app..} 配下の全クラス（起点はドメインを持つクラス）。
 * D-3 の {@code isTransactional()} ガードを外した点だけが差分で、それ以外の判定・除外は同一。
 *
 * <h2>違反条件（直接依存のみ）</h2>
 * <p>そのクラスの {@code getDirectDependenciesFromSelf()} を走査し、依存先が
 * {@code ..repository..} パッケージの型で、その {@link DomainPackages#domainOf(String) ドメイン}
 * が自ドメインと異なり、かつ {@code common} でもない場合に違反 1 件とする。
 * <b>推移呼び出しは追わない</b>（直接依存のみ＝誤検知抑制）。発生元が {@code common} 共有ドメイン・
 * ドメイン外（{@code com.mannschaft.app} 直下）の場合は対象外。
 *
 * <h2>凍結方式（FreezingArchRule）</h2>
 * <p>既存の越境 Repository 依存は多数存在するため、{@link FreezingArchRule} で既存違反を
 * 凍結ストア（{@code src/test/resources/archunit_store/}）へ記録し、<b>新規違反のみ</b> fail
 * させる。{@link CrossDomainTransactionalArchTest}（D-3）等と同一ストアディレクトリを共用し、
 * {@code stored.rules} に本ルール（D-5）のエントリが独立の UUID で追加される。既存越境を
 * 解消すると {@code freeze.refreeze=false} のデフォルト挙動でストアが自動縮小される（chip-away）。
 */
@AnalyzeClasses(
    packages = "com.mannschaft.app",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class CrossDomainRepositoryDependencyArchTest {

    private static final String REPOSITORY_MARKER = ".repository";

    @ArchTest
    static final ArchRule classes_should_not_depend_on_other_domain_repositories =
        FreezingArchRule.freeze(
            classes().that().resideInAPackage("com.mannschaft.app..")
                .should(notDependOnOtherDomainRepositories())
                .because("CLAUDE.md ドメイン境界の原則 — ドメイン間のデータ取得は Service 経由とし、"
                    + "別ドメインの Repository へ直接依存してはならない（@Transactional の有無を問わない）。"
                    + "越境 Repository 依存は将来のシャーディングでドメイン別データストア分割を阻害する。"
                    + "既存の越境は凍結し、新規越境のみ fail させる")
                // 凍結ストアの照合キー（rule description）を固定する。
                .as("no cross-domain repository dependency (D-5)"));

    // ------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------

    private static ArchCondition<JavaClass> notDependOnOtherDomainRepositories() {
        return new ArchCondition<>("not depend on other-domain repositories") {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                String sourceDomain = DomainPackages.domainOf(clazz.getPackageName());
                if (sourceDomain == null || DomainPackages.isSharedDomain(sourceDomain)) {
                    return;
                }
                for (Dependency dep : clazz.getDirectDependenciesFromSelf()) {
                    JavaClass target = dep.getTargetClass();
                    String targetPkg = target.getPackageName();
                    if (!isRepositoryPackage(targetPkg)) {
                        continue;
                    }
                    String targetDomain = DomainPackages.domainOf(targetPkg);
                    if (targetDomain == null
                            || DomainPackages.isSharedDomain(targetDomain)
                            || targetDomain.equals(sourceDomain)) {
                        continue;
                    }
                    String message = String.format(
                        "%s (domain '%s') depends on other-domain repository %s (domain '%s')",
                        clazz.getName(), sourceDomain,
                        target.getName(), targetDomain);
                    events.add(SimpleConditionEvent.violated(dep, message));
                }
            }
        };
    }

    /** {@code ..repository} または {@code ..repository.*} 配下かどうか。 */
    private static boolean isRepositoryPackage(String packageName) {
        return packageName.contains(REPOSITORY_MARKER + ".")
            || packageName.endsWith(REPOSITORY_MARKER);
    }
}
