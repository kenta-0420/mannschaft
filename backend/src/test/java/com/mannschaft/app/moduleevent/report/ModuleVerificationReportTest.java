package com.mannschaft.app.moduleevent.report;

import com.mannschaft.app.common.architecture.ArchUnitTestTag;
import com.mannschaft.modulithfixture.alpha.api.ApiType;
import com.mannschaft.modulithfixture.alpha.api.dto.DtoType;
import com.mannschaft.modulithfixture.alpha.api.hidden.UnexportedType;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 小検体の実verifyから、違反ありでも成功する非空レポートの契約を検証する。 */
@Tag(ArchUnitTestTag.ARCHUNIT)
class ModuleVerificationReportTest {
    @TempDir Path temporary;

    @Test
    void 実verifyは検体の内部参照と循環を違反として返す() {
        Throwable failure = catchThrowable(() -> fixtureModules().verify());

        assertThat(failure).isInstanceOf(Violations.class);
        var violations = (Violations) failure;
        assertThat(violations.getMessages()).isNotEmpty();
        assertThat(violations.getMessage()).contains("non-exposed type", "Cycle detected");
    }

    @Test
    void 違反があっても処理は成功し非空レポートを残す() throws IOException {
        Path report = temporary.resolve("reports/modulith/fixture.txt");

        assertThatCode(() -> ModuleVerificationReport.write(fixtureModules(), report))
                .doesNotThrowAnyException();
        assertThat(Files.readString(report)).isNotBlank()
                .contains("com.mannschaft.modulithfixture", "non-exposed type", "Cycle detected");
    }

    @Test
    void 同名apiは親と明示子を統合するが未宣言の孫には再帰しない() {
        var api = fixtureModules().getModuleByName("alpha").orElseThrow()
                .getNamedInterfaces().getByName("api").orElseThrow();

        assertThat(api.contains(ApiType.class)).isTrue();
        assertThat(api.contains(DtoType.class)).isTrue();
        assertThat(api.contains(UnexportedType.class)).isFalse();
    }

    @Test
    void レポート保存の環境障害は違反として握りつぶさない() throws IOException {
        Path blockedParent = temporary.resolve("既存ファイル");
        Files.writeString(blockedParent, "親directoryではない");

        Throwable failure = catchThrowable(() -> ModuleVerificationReport.write(
                fixtureModules(), blockedParent.resolve("fixture.txt")));

        assertThat(failure).isInstanceOf(IOException.class);
    }

    private ApplicationModules fixtureModules() {
        // production全体を取り込まず、test配下の独立検体だけを明示的に解析する。
        // verify済み状態もcacheされるため、identityの異なる公開ImportOptionで試験間を分離する。
        ImportOption includeFixtureClasses = new ImportOption() {
            @Override
            public boolean includes(Location location) {
                return true;
            }
        };
        return ApplicationModules.of("com.mannschaft.modulithfixture", includeFixtureClasses);
    }
}
