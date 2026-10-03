package com.mannschaft.app.common.architecture.fixtures.importguard;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

/**
 * 違反見本 (b): 本番ルートをローカル変数に入れ、途中で別の取り込み（importClasses）を挟んでから
 * その変数を {@code importPackages} に渡す。番人 {@code ProductionClassImportGuardTest} の自己検証専用。実行はしない。
 */
public final class WholeProductionImportViaLocalAfterOtherImportSample {

    JavaClasses load() {
        String root = "com.mannschaft.app";
        JavaClasses explicit = new ClassFileImporter()
                .importClasses(WholeProductionImportViaLocalAfterOtherImportSample.class);
        JavaClasses whole = new ClassFileImporter().importPackages(root);
        return explicit.size() > 0 ? whole : explicit;
    }
}
