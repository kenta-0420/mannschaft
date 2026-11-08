package com.mannschaft.app.moduleevent.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;

/** 実verifyの境界違反だけをレポートし、解析・保存の障害は呼出側へ伝える。 */
final class ModuleVerificationReport {
    private ModuleVerificationReport() {
    }

    static Path write(ApplicationModules modules, Path report) throws IOException {
        Objects.requireNonNull(modules, "modules");
        String result = "境界違反なし\n";
        try {
            modules.verify();
        } catch (Violations violations) {
            result = violations.getMessage() + "\n";
        }
        Files.createDirectories(report.getParent());
        Files.writeString(report, result, StandardCharsets.UTF_8);
        return report;
    }
}
