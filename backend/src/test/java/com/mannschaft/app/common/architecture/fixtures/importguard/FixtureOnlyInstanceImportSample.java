package com.mannschaft.app.common.architecture.fixtures.importguard;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * 許可見本: fixture パッケージを instance フィールドで、本番の一部パッケージとクラス列挙を
 * ローカル変数で取り込む。番人 {@code ProductionClassImportGuardTest} は検出してはならない。実行はしない。
 */
public final class FixtureOnlyInstanceImportSample {

    private final JavaClasses fixtures = new ClassFileImporter()
            .importPackages("com.mannschaft.app.common.architecture.fixtures.importguard");

    JavaClasses fixtures() {
        return fixtures;
    }

    JavaClasses partialProduction() {
        JavaClasses schedule = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.mannschaft.app.schedule");
        return schedule;
    }

    JavaClasses explicitClasses() {
        JavaClasses explicit = new ClassFileImporter().importClasses(FixtureOnlyInstanceImportSample.class);
        return explicit;
    }
}
