package com.mannschaft.app.common.architecture.fixtures.importguard;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

/**
 * 違反見本 (a): {@link JavaClasses} を static フィールドで保持する（取り込み自体は許可形の
 * {@code importClasses}）。番人 {@code ProductionClassImportGuardTest} の自己検証専用。実行はしない。
 */
public final class StaticJavaClassesFieldSample {

    private static JavaClasses classes;

    private StaticJavaClassesFieldSample() {
    }

    static JavaClasses load() {
        classes = new ClassFileImporter().importClasses(StaticJavaClassesFieldSample.class);
        return classes;
    }
}
