package com.mannschaft.app.common.architecture.fixtures.importguard;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

/**
 * 違反見本 (b): 分岐で合流した配列の別名を通して本番ルートを書き込み、元の配列を {@code importPackages} に渡す。
 * 番人 {@code ProductionClassImportGuardTest} の自己検証専用。実行はしない。
 */
public final class WholeProductionImportViaMergedArrayAliasSample {

    JavaClasses load(boolean flag) {
        String[] roots = {"com.mannschaft.app.schedule"};
        String[] other = {"com.mannschaft.app.todo"};
        String[] alias = flag ? roots : other;
        alias[0] = "com.mannschaft.app";
        return new ClassFileImporter().importPackages(roots);
    }
}
