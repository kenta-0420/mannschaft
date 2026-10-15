package com.mannschaft.app.common.architecture;

import com.mannschaft.app.resident.transitivetx.ClassTransactionalFixture;
import org.junit.jupiter.api.Tag;
import com.mannschaft.app.resident.transitivetx.InheritedClassTransactionalFixture;
import com.mannschaft.app.resident.transitivetx.TransitiveTransactionalFixture;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** D-3T の本番判定ロジックを fixture で固定するメタテスト。 */
@Tag(ArchUnitTestTag.ARCHUNIT)
class CrossDomainTransactionalTransitiveGuardConditionTest {

    private final JavaClasses fixtureClasses = new ClassFileImporter().importPackages(
        "com.mannschaft.app.resident.transitivetx",
        "com.mannschaft.app.resident.repository",
        "com.mannschaft.app.membership.transitivetx",
        "com.mannschaft.app.role.transitivetx",
        "com.mannschaft.app.proxy.repository",
        "com.mannschaft.app.common.transitivetx");

    @Test
    void メソッド委譲を三段辿って別ドメインRepositoryを検出する() {
        List<CrossDomainTransactionalTransitiveArchTest.TransitiveViolation> violations =
            detect(TransitiveTransactionalFixture.class);
        assertThat(violations).filteredOn(v -> v.entryFullName().contains("execute"))
            .singleElement()
            .satisfies(v -> {
                assertThat(v.repositoryType())
                    .isEqualTo("com.mannschaft.app.proxy.repository.TransitiveProxyRepository");
                assertThat(v.diagnosticPath())
                    .contains("MembershipBridge.forward()")
                    .contains("RoleBridge.forward()")
                    .contains("TransitiveProxyRepository.save()");
            });
    }

    @Test
    void constructor呼び出しとcommon中継も探索する() {
        assertThat(detect(TransitiveTransactionalFixture.class))
            .filteredOn(v -> v.entryFullName().contains("constructThroughCommon"))
            .singleElement()
            .satisfies(v -> assertThat(v.diagnosticPath())
                .contains("CommonConstructorBridge.<init>()")
                .contains("TransitiveProxyRepository.<init>()"));
    }

    @Test
    void 循環とdiamondは停止し入口とRepository型で一件へ重複排除する() {
        List<CrossDomainTransactionalTransitiveArchTest.TransitiveViolation> violations =
            detect(TransitiveTransactionalFixture.class);
        assertThat(violations.stream().filter(v -> v.entryFullName().contains("cycle"))).hasSize(1);
        assertThat(violations.stream().filter(v -> v.entryFullName().contains("diamond"))).hasSize(1);
    }

    @Test
    void 同一ドメインRepositoryと非Transactional入口は違反にしない() {
        assertThat(detect(TransitiveTransactionalFixture.class))
            .noneMatch(v -> v.repositoryType().contains("TransitiveResidentRepository"))
            .noneMatch(v -> v.entryFullName().contains("notTransactional"));
    }

    @Test
    void readOnlyとprivateHelperは通常のTransactional入口として検出する() {
        assertThat(detect(TransitiveTransactionalFixture.class))
            .extracting(CrossDomainTransactionalTransitiveArchTest.TransitiveViolation::entryFullName)
            .anyMatch(name -> name.contains("readOnly"))
            .anyMatch(name -> name.contains("throughPrivateHelper"));
    }

    @Test
    void 未呼出しの別overloadは探索しない() {
        assertThat(detect(TransitiveTransactionalFixture.class).stream()
            .filter(v -> v.entryFullName().contains("safeOverload"))).isEmpty();
    }

    @Test
    void 継承Repositoryメソッドへの呼び出しも検出する() {
        assertThat(detect(TransitiveTransactionalFixture.class))
            .filteredOn(v -> v.entryFullName().contains("inheritedRepository"))
            .singleElement()
            .satisfies(v -> assertThat(v.repositoryType()).contains("InheritedProxyRepository"));
    }

    @Test
    void 空入口と解決不能なinterface呼び出しは例外なく違反ゼロになる() {
        assertThat(detect(TransitiveTransactionalFixture.class))
            .noneMatch(v -> v.entryFullName().contains("empty"))
            .noneMatch(v -> v.entryFullName().contains("unresolved"));
    }

    @Test
    void classレベルTransactionalは宣言メソッドを入口にする() {
        assertThat(detect(ClassTransactionalFixture.class))
            .singleElement()
            .satisfies(v -> assertThat(v.entryFullName()).contains("execute"));
    }

    @Test
    void classレベルTransactionalは継承したアプリ内メソッドも論理入口にする() {
        assertThat(detect(InheritedClassTransactionalFixture.class))
            .singleElement()
            .satisfies(v -> assertThat(v.entryFullName())
                .contains("InheritedClassTransactionalFixture.inheritedExecute")
                .contains("inherited from"));
    }

    private List<CrossDomainTransactionalTransitiveArchTest.TransitiveViolation> detect(
            Class<?> fixtureType) {
        JavaClass fixture = fixtureClasses.get(fixtureType);
        return CrossDomainTransactionalTransitiveArchTest.findViolations(fixture);
    }
}