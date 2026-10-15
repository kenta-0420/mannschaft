package com.mannschaft.app.common.architecture.fixtures.importguard;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

/**
 * 許可見本: 取り込みと無関係な空文字定数（本番ルートの祖先と同じ文字列）を使った後に、fixture パッケージだけを
 * 取り込む。番人 {@code ProductionClassImportGuardTest} は実引数だけを見るので検出してはならない。実行はしない。
 */
public final class UnrelatedEmptyStringThenFixtureImportSample {

    JavaClasses load() {
        assertThat("").isEmpty();
        return new ClassFileImporter()
                .importPackages("com.mannschaft.app.common.architecture.fixtures.importguard");
    }
}
