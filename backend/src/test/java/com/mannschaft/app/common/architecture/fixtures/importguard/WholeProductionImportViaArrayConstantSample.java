package com.mannschaft.app.common.architecture.fixtures.importguard;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

/**
 * 違反見本 (b): 本番ルートを static 定数配列経由で {@code importPackages} する。
 * 番人 {@code ProductionClassImportGuardTest} の自己検証専用。実行はしない。
 */
public final class WholeProductionImportViaArrayConstantSample {

    private static final String[] ROOTS = {"com.mannschaft.app"};

    JavaClasses load() {
        return new ClassFileImporter().importPackages(ROOTS);
    }
}
