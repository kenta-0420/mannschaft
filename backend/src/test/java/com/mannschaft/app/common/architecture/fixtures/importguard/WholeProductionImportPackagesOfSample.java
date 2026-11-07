package com.mannschaft.app.common.architecture.fixtures.importguard;

import com.mannschaft.app.MannschaftApplication;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

/**
 * 違反見本 (b): ルートパッケージのクラスを起点に {@code importPackagesOf} する（本番全体と同じ）。
 * 番人 {@code ProductionClassImportGuardTest} の自己検証専用。実行はしない。
 */
public final class WholeProductionImportPackagesOfSample {

    JavaClasses load() {
        return new ClassFileImporter().importPackagesOf(MannschaftApplication.class);
    }
}
