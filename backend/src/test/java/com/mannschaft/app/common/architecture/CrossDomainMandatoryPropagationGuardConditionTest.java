package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.mandatoryport.MandatoryPortCommonFixtures;
import com.mannschaft.app.membership.mandatoryport.MandatoryPortMembershipFixtures;
import com.mannschaft.app.notification.mandatoryport.MandatoryPortNotificationFixtures;
import com.mannschaft.app.resident.mandatoryport.MandatoryPortResidentFixtures;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-3P（{@link CrossDomainMandatoryPropagationArchTest}）の判定ロジックを検体で固定するメタテスト
 * （OG01・OG02 と D-3P-3 の陽性・陰性）。検体は test ソースの {@code *.mandatoryport} パッケージ。
 */
@Tag(ArchUnitTestTag.ARCHUNIT)
@DisplayName("D-3P 番人の判定（検体による陽性・陰性）")
class CrossDomainMandatoryPropagationGuardConditionTest {

    private final JavaClasses fixtureClasses = new ClassFileImporter().importPackages(
            "com.mannschaft.app.resident.mandatoryport",
            "com.mannschaft.app.membership.mandatoryport",
            "com.mannschaft.app.common.mandatoryport",
            "com.mannschaft.app.notification.mandatoryport");

    // =====================================================================
    // OG01 D-3P-1
    // =====================================================================

    @Test
    @DisplayName("OG01 D-3P-1: 別ドメインの MANDATORY をメソッド・クラス・親クラス・interface のどの宣言で持っていても、呼び出しを検出する")
    void og01_別ドメインのMANDATORY呼び出しを宣言の形によらず検出する() {
        List<CrossDomainMandatoryPropagationArchTest.Violation> violations =
                d3p1(MandatoryPortMembershipFixtures.CrossDomainCaller.class);

        assertThat(violations).extracting(CrossDomainMandatoryPropagationArchTest.Violation::auditKey)
                .as("陽性の5件（メソッド・クラス・親クラス・interface の宣言と、interface の MANDATORY がクラスの REQUIRED に勝つ競合）だけ")
                .hasSize(5)
                .anyMatch(k -> k.contains("callMethodLevel"))
                .anyMatch(k -> k.contains("callClassLevel"))
                .anyMatch(k -> k.contains("callInherited"))
                .anyMatch(k -> k.contains("callInterfaceDeclared"))
                .anyMatch(k -> k.contains("callInterfaceMandatoryOverClassRequired"));
        assertThat(violations).allMatch(v -> v.rule().equals("D-3P-1"))
                .allMatch(v -> v.message().contains("domain 'membership'") && v.message().contains("domain 'resident'"));
    }

    @Test
    @DisplayName("OG01 D-3P-1: REQUIRED・無印・common の MANDATORY・イベント経由の呼び出しは違反にしない")
    void og01_REQUIREDと無印とcommonとイベント経由は違反にしない() {
        assertThat(d3p1(MandatoryPortMembershipFixtures.CrossDomainCaller.class))
                .extracting(CrossDomainMandatoryPropagationArchTest.Violation::auditKey)
                .noneMatch(k -> k.contains("callRequired"))
                .noneMatch(k -> k.contains("callPlain"))
                .noneMatch(k -> k.contains("callCommon"))
                .noneMatch(k -> k.contains("publishEvent"));
    }

    @Test
    @DisplayName("OG01 D-3P-1: 同じドメインからの呼び出しと、common からの呼び出しは違反にしない")
    void og01_同一ドメインとcommonからの呼び出しは違反にしない() {
        assertThat(d3p1(MandatoryPortResidentFixtures.SameDomainCaller.class)).isEmpty();
        assertThat(d3p1(MandatoryPortCommonFixtures.CommonCaller.class)).isEmpty();
    }

    // =====================================================================
    // OG02 D-3P-2
    // =====================================================================

    @Test
    @DisplayName("OG02 D-3P-2: 別ドメインのポートをメソッド・クラス・親クラスの MANDATORY で実装すると検出する")
    void og02_逆向きポートを宣言の形によらず検出する() {
        assertThat(d3p2(MandatoryPortResidentFixtures.MethodLevelReversePortAdapter.class))
                .singleElement()
                .satisfies(v -> assertThat(v.auditKey()).endsWith("MethodLevelReversePortAdapter.lock"));
        assertThat(d3p2(MandatoryPortResidentFixtures.ClassLevelReversePortAdapter.class))
                .singleElement()
                .satisfies(v -> assertThat(v.auditKey()).endsWith("ClassLevelReversePortAdapter.lock"));
        assertThat(d3p2(MandatoryPortResidentFixtures.InheritedReversePortAdapter.class))
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.auditKey()).endsWith("InheritedReversePortAdapter.lock");
                    assertThat(v.message()).contains("MembershipLockPort").contains("domain 'membership'");
                });
    }

    @Test
    @DisplayName("OG02 D-3P-2: ポートのメソッドが MANDATORY・実装メソッドが無印・実装クラスが REQUIRED なら、Spring と同じく MANDATORY とみなして検出する")
    void og02_ポートのメソッドのMANDATORYは実装クラスのREQUIREDより先に当たる() {
        assertThat(d3p2(MandatoryPortResidentFixtures.InterfaceMandatoryOverClassRequiredAdapter.class))
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.auditKey()).endsWith("InterfaceMandatoryOverClassRequiredAdapter.lock");
                    assertThat(v.message()).contains("MembershipMandatoryLockPort");
                });
    }

    @Test
    @DisplayName("OG02 D-3P-2: ポートのメソッドが MANDATORY でも、実装メソッドに REQUIRED を明示していれば違反にしない")
    void og02_実装メソッドのREQUIREDはポートのMANDATORYより先に当たる() {
        assertThat(d3p2(MandatoryPortResidentFixtures.MethodRequiredOverInterfaceMandatoryAdapter.class)).isEmpty();
    }

    @Test
    @DisplayName("OG02 D-3P-2: REQUIRED での実装・common のポート・同じドメインのポートは違反にしない")
    void og02_REQUIREDとcommonと同一ドメインのポートは違反にしない() {
        assertThat(d3p2(MandatoryPortResidentFixtures.RequiredReversePortAdapter.class)).isEmpty();
        assertThat(d3p2(MandatoryPortResidentFixtures.CommonPortAdapter.class)).isEmpty();
        assertThat(d3p2(MandatoryPortResidentFixtures.SameDomainPortAdapter.class)).isEmpty();
    }

    // =====================================================================
    // D-3P-3
    // =====================================================================

    @Test
    @DisplayName("D-3P-3: notification 以外から enqueueInCurrentTransaction・enqueueInOwnTransaction を呼ぶと検出し、独立コミットの enqueue は許す")
    void d3p3_通知ドメインのtx参加型の登録口の呼び出しを検出する() {
        assertThat(d3p3(MandatoryPortMembershipFixtures.FanoutEnqueuer.class))
                .extracting(CrossDomainMandatoryPropagationArchTest.Violation::auditKey)
                .hasSize(2)
                .anyMatch(k -> k.contains("enqueueInCallerTransaction"))
                .anyMatch(k -> k.contains("enqueueInOwn"))
                .noneMatch(k -> k.contains("enqueueIsolated"));
    }

    @Test
    @DisplayName("D-3P-3: notification 以外から NotificationOutboxIngestService に依存すると検出し、通知ドメインの内側は許す")
    void d3p3_取り込みサービスへの依存を検出し通知ドメイン内は許す() {
        assertThat(d3p3(MandatoryPortMembershipFixtures.IngestDependent.class))
                .singleElement()
                .satisfies(v -> assertThat(v.message()).contains("NotificationOutboxIngestService"));
        assertThat(d3p3(MandatoryPortNotificationFixtures.InternalEnqueuer.class)).isEmpty();
    }

    private List<CrossDomainMandatoryPropagationArchTest.Violation> d3p1(Class<?> type) {
        return detect(type, CrossDomainMandatoryPropagationArchTest::findMandatoryCallViolations);
    }

    private List<CrossDomainMandatoryPropagationArchTest.Violation> d3p2(Class<?> type) {
        return detect(type, CrossDomainMandatoryPropagationArchTest::findReversePortViolations);
    }

    private List<CrossDomainMandatoryPropagationArchTest.Violation> d3p3(Class<?> type) {
        return detect(type, CrossDomainMandatoryPropagationArchTest::findNotificationEntryViolations);
    }

    private List<CrossDomainMandatoryPropagationArchTest.Violation> detect(
            Class<?> type,
            Function<com.tngtech.archunit.core.domain.JavaClass,
                    List<CrossDomainMandatoryPropagationArchTest.Violation>> finder) {
        return finder.apply(fixtureClasses.get(type));
    }
}
