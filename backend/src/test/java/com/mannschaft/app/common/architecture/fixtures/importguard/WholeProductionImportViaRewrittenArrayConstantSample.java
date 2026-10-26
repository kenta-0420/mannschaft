package com.mannschaft.app.common.architecture.fixtures.importguard;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

/**
 * 違反見本 (b): 初期値は本番の一部だけを指す static final 配列を、メソッド内で本番ルートへ書き換えてから
 * {@code importPackages} に渡す。番人 {@code ProductionClassImportGuardTest} の自己検証専用。実行はしない。
 */
public final class WholeProductionImportViaRewrittenArrayConstantSample {

    private static final String[] ROOTS = {"com.mannschaft.app.schedule"};

    JavaClasses load() {
        ROOTS[0] = "com.mannschaft.app";
        return new ClassFileImporter().importPackages(ROOTS);
    }
}
