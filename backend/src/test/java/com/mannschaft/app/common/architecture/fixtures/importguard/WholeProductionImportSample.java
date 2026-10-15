package com.mannschaft.app.common.architecture.fixtures.importguard;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * 違反見本 (b): 本番ルートを文字列定数で {@code importPackages} する（ローカル変数でも違反）。
 * 番人 {@code ProductionClassImportGuardTest} の自己検証専用。実行はしない。
 */
public final class WholeProductionImportSample {

    JavaClasses load() {
        JavaClasses imported = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.mannschaft.app");
        return imported;
    }
}
