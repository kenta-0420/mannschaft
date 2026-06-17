package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

import org.springframework.transaction.annotation.Transactional;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * クロスドメイン {@code @Transactional} の番人テスト（D-3）: <b>{@code @Transactional} が
 * 付いたクラスは、別ドメインの {@code repository} に直接依存してはならない</b>。
 *
 * <p>設計指針: {@code CLAUDE.md}「DB 設計の原則 #5」—
 * 「{@code @Transactional} メソッドが複数ドメインの Repository をまたぐ場合は設計を見直す。
 * やむを得ずまたぐ場合はコメントで理由を明記し、将来のイベント駆動化候補として記録する」。
 * 1 トランザクションが複数ドメインの DB をまたぐと、将来の水平シャーディング時に
 * 分散トランザクション／越境デッドロックの温床になるため、機械的に検知する。
 *
 * <h2>検査対象</h2>
 * <p>{@code org.springframework.transaction.annotation.Transactional} が
 * <b>クラスまたはメソッド</b>に付いているクラスを発生元とみなす。Spring の
 * {@code @Transactional} はクラス付与がメソッドへ継承される点を考慮し、
 * 「クラスに付いている」場合もそのクラスを対象にする。
 *
 * <h2>違反条件（直接依存のみ）</h2>
 * <p>そのクラスの {@code getDirectDependenciesFromSelf()} を走査し、依存先が
 * {@code ..repository..} パッケージの型で、その {@link DomainPackages#domainOf(String) ドメイン}
 * が自ドメインと異なり、かつ {@code common} でもない場合に違反 1 件とする。
 * <b>推移呼び出し（A→B→他ドメイン repo）は追わない</b>（直接依存のみ＝誤検知抑制）。
 *
 * <h2>凍結方式（FreezingArchRule）</h2>
 * <p>既存の越境 {@code @Transactional} は多数存在するため、{@link FreezingArchRule} で
 * 既存違反を凍結ストア（{@code src/test/resources/archunit_store/}）へ記録し、
 * <b>新規違反のみ</b> fail させる。{@link CrossDomainEntityImportArchTest} 等と
 * 同一ストアディレクトリを共用し、{@code stored.rules} に本ルールのエントリが追加される。
 * 既存越境を解消すると {@code freeze.refreeze=false} のデフォルト挙動でストアが
 * 自動縮小される（chip-away）。
 */
@AnalyzeClasses(
    packages = "com.mannschaft.app",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class CrossDomainTransactionalArchTest {

    private static final String REPOSITORY_MARKER = ".repository";

    @ArchTest
    static final ArchRule transactional_should_not_span_other_domain_repositories =
        FreezingArchRule.freeze(
            classes().that().resideInAPackage("com.mannschaft.app..")
                .should(notDependOnOtherDomainRepositories())
                .because("CLAUDE.md DB 設計の原則 #5 — @Transactional は単一ドメイン内に"
                    + "閉じること。別ドメインの Repository に直接依存する越境トランザクションは"
                    + "将来のシャーディングで分散トランザクション/デッドロックの温床になる。"
                    + "既存の越境は凍結し、新規越境のみ fail させる")
                // 凍結ストアの照合キー（rule description）を固定する。
                .as("transactional should not span other-domain repositories (D-3)"));

    // ------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------

    private static ArchCondition<JavaClass> notDependOnOtherDomainRepositories() {
        return new ArchCondition<>("not depend on other-domain repositories within @Transactional") {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                String sourceDomain = DomainPackages.domainOf(clazz.getPackageName());
                if (sourceDomain == null || DomainPackages.isSharedDomain(sourceDomain)) {
                    return;
                }
                if (!isTransactional(clazz)) {
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
                        "%s (domain '%s', @Transactional) depends on other-domain repository "
                            + "%s (domain '%s')",
                        clazz.getName(), sourceDomain,
                        target.getName(), targetDomain);
                    events.add(SimpleConditionEvent.violated(dep, message));
                }
            }
        };
    }

    /** クラス自身、または任意のメソッドに Spring {@code @Transactional} が付いているか。 */
    private static boolean isTransactional(JavaClass clazz) {
        if (clazz.isAnnotatedWith(Transactional.class)) {
            return true;
        }
        for (JavaMethod method : clazz.getMethods()) {
            if (method.isAnnotatedWith(Transactional.class)) {
                return true;
            }
        }
        return false;
    }

    /** {@code ..repository} または {@code ..repository.*} 配下かどうか。 */
    private static boolean isRepositoryPackage(String packageName) {
        return packageName.contains(REPOSITORY_MARKER + ".")
            || packageName.endsWith(REPOSITORY_MARKER);
    }
}
