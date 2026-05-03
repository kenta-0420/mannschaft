package com.mannschaft.app.proxy;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * F14.1 代理入力: アーキテクチャ制約テスト（ArchUnit）。
 * 集計汚染防止のため ProxyInputContext の不正利用を CI で検知する。
 */
@AnalyzeClasses(
    packages = "com.mannschaft.app",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class ProxyInputArchitectureTest {

    /** Repository 層は ProxyInputContext に直接依存してはならない */
    @ArchTest
    static final ArchRule repositoryShouldNotDependOnProxyInputContext =
        noClasses()
            .that().resideInAPackage("..repository..")
            .should().dependOnClassesThat()
                .haveFullyQualifiedName("com.mannschaft.app.proxy.ProxyInputContext")
            .because("Repository層はProxyInputContextに依存してはならない。" +
                     "代理入力フラグはService層でのみ設定する（F14.1）");

    /** Controller 層は ProxyInputRecordRepository に直接依存してはならない */
    @ArchTest
    static final ArchRule controllerShouldNotDirectlyAccessProxyInputRecordRepository =
        noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat()
                .haveSimpleName("ProxyInputRecordRepository")
            .because("ProxyInputRecordRepository はService層経由でのみ使用し、" +
                     "Controllerから直接操作しない（F14.1）");
}
