package com.mannschaft.app.common.architecture.fixtures.importguard;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

/**
 * 違反見本 (b): 定数に解決できない引数（メソッドの戻り値）で {@code importPackages} する。本番全体でないことを
 * 示せないので違反とする（fail closed）。番人 {@code ProductionClassImportGuardTest} の自己検証専用。実行はしない。
 */
public final class UnresolvableImportArgumentSample {

    JavaClasses load() {
        return new ClassFileImporter().importPackages(System.getProperty("importguard.package"));
    }
}
