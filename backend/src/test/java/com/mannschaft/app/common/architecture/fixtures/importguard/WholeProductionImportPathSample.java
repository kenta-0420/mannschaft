package com.mannschaft.app.common.architecture.fixtures.importguard;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Path;

/**
 * 違反見本 (b): 本番のクラス出力ディレクトリを {@code importPath} で場所単位に取り込む。
 * 番人 {@code ProductionClassImportGuardTest} の自己検証専用。実行はしない。
 */
public final class WholeProductionImportPathSample {

    JavaClasses load() {
        return new ClassFileImporter().importPath(Path.of("build/classes/java/main"));
    }
}
