package com.mannschaft.app.moduleevent.report;

import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.modulith.core.ApplicationModules;

/**
 * 全体解析をArchUnitの共有解析と別のJavaExec JVMで実行する入口。
 * 公開APIは既存JavaClassesを再利用できないため、JUnitからは起動しない。
 * 出力はPRとnightlyの既存arch結果artifactに含まれる独立txtとする。
 */
public final class ProductionModuleVerificationReport {
    private ProductionModuleVerificationReport() {
    }

    public static void main(String[] args) throws IOException {
        var modules = ApplicationModules.of("com.mannschaft.app", ImportOption.Predefined.DO_NOT_INCLUDE_TESTS);
        ModuleVerificationReport.write(modules, Path.of("build/test-results/archTest/modulith/production.txt"));
    }
}
