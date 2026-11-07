package com.mannschaft.app.common.architecture;

import external.transitivetx.ExternalPortBridge;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.DescriptorDefaultPort;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.DescriptorDefaultBridge;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.SuperDefaultBridge;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.MixedDefaultBridge;
import com.mannschaft.app.proxy.repository.InheritedProxyRepository;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.SafeDefaultParent;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.UnsafeDefaultChild;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.ChildDefaultBridge;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.ClassDefaultOverride;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.SafeDefaultChild;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.SafeChildDefaultBridge;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.ChildDefaultEntry;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.DefaultPort;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.DefaultEntry;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.FirstDefaultBridge;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.SecondDefaultBridge;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.ExcludedImplementationReference;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.Port;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.ChildPort;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.AbstractBridge;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.SingleBridge;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.SecondBridge;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.SafeOverrideBridge;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.OverloadedBridge;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.InterfaceEntry;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.ConcreteBridge;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.UnsafeConcreteSubclass;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.ConcreteEntry;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.RecursiveBridge;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.ComposedTx;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.NestedTx;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.PlainListener;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.CycleA;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.CycleB;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.ComposedMethodEntry;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.NestedMethodEntry;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.ComposedClassEntry;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.NestedClassEntry;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.NonTransactionalListener;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.SameDomainWriter;
import com.mannschaft.app.resident.transitivetx.L1G0TransactionalFixture.CrossDomainWriter;
import com.mannschaft.app.proxy.repository.TransitiveProxyRepository;
import com.mannschaft.app.resident.repository.TransitiveResidentRepository;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

/** AC-1〜7: storeを使わず本番の条件を検体集合で実行する試練。 */
@Tag(ArchUnitTestTag.ARCHUNIT)
class CrossDomainTransactionalL1G0ConditionTest {
    @Test
    void 唯一の具象実装をinterface越しに展開する() {
        assertThat(d3t(InterfaceEntry.class, SingleBridge.class))
            .filteredOn(v -> v.contains(".execute(")).hasSize(1);
    }

    @Test
    void 実装ゼロは展開しない() {
        assertThat(d3t(InterfaceEntry.class)).isEmpty();
    }

    @Test
    void 実装二件以上はどちらも展開しない() {
        assertThat(d3t(InterfaceEntry.class, SingleBridge.class, SecondBridge.class)).isEmpty();
    }

    @Test
    void 子interfaceと抽象基底は候補数へ含めず継承本体を辿る() {
        assertThat(d3t(InterfaceEntry.class, SingleBridge.class))
            .filteredOn(v -> v.contains(".child(")).hasSize(1);
    }

    @Test
    void 唯一の実装のoverrideを継承本体より優先する() {
        assertThat(d3t(InterfaceEntry.class, SafeOverrideBridge.class)).isEmpty();
    }

    @Test
    void 呼んだsignatureだけを展開して別overloadを辿らない() {
        assertThat(d3t(InterfaceEntry.class, OverloadedBridge.class)).isEmpty();
    }

    @Test
    void 通常具象呼び出しはsubclassへ展開しない() {
        assertThat(d3t(ConcreteEntry.class, ConcreteBridge.class, UnsafeConcreteSubclass.class)).isEmpty();
    }

    @Test
    void import集合外の具象実装は実装数を変えない() {
        JavaClasses candidates = imported(InterfaceEntry.class, SingleBridge.class);
        assertThat(candidates.contain(SecondBridge.class.getName())).isFalse();
        assertThat(candidates.get(ExcludedImplementationReference.class).getField("excluded").getRawType()
            .getName()).isEqualTo(SecondBridge.class.getName());
        assertThat(candidates.get(AbstractBridge.class).getAllSubclasses())
            .extracting(JavaClass::getName).contains(SecondBridge.class.getName());

        assertThat(detect(CrossDomainTransactionalTransitiveArchTest.notReachOtherDomainRepositories(), candidates))
            .filteredOn(v -> v.contains(".execute(")).hasSize(1);
    }

    @Test
    void 外部packageの具象実装候補を数えない() {
        assertThat(d3t(InterfaceEntry.class, SingleBridge.class, ExternalPortBridge.class))
            .filteredOn(v -> v.contains(".execute(")).hasSize(1);
    }
    @Test
    void interface再帰とdiamondは停止し入口とrepoで一件になる() {
        assertThat(d3t(InterfaceEntry.class, RecursiveBridge.class))
            .filteredOn(v -> v.contains(".diamond(")).singleElement()
            .isEqualTo("@Transactional entry " + InterfaceEntry.class.getName()
                + ".diamond(" + Port.class.getName() + ") (domain 'resident') reaches other-domain repository "
                + TransitiveProxyRepository.class.getName() + " (domain 'proxy') [D-3T]");
    }

    @Test
    void 実装候補ゼロでも静的解決できるdefault本体を保持する() {
        assertThat(d3t(DefaultEntry.class, DefaultPort.class)).hasSize(1);
    }

    @Test
    void 実装候補二件でも静的解決できるdefault本体を保持する() {
        assertThat(d3t(DefaultEntry.class, DefaultPort.class,
            FirstDefaultBridge.class, SecondDefaultBridge.class)).hasSize(1);
    }

    @Test
    void 単一実装が継承した子interfaceの越境default本体を辿る() {
        assertThat(d3t(ChildDefaultEntry.class, SafeDefaultParent.class,
            UnsafeDefaultChild.class, ChildDefaultBridge.class)).hasSize(1);
    }

    @Test
    void 単一実装では安全な子defaultがoverrideした親の越境defaultを辿らない() {
        assertThat(d3t(DefaultEntry.class, DefaultPort.class,
            SafeDefaultChild.class, SafeChildDefaultBridge.class)).isEmpty();
    }

    @Test
    void 子interfaceのdefaultより具象クラスのoverrideを優先する() {
        assertThat(d3t(ChildDefaultEntry.class, SafeDefaultParent.class,
            UnsafeDefaultChild.class, ClassDefaultOverride.class)).isEmpty();
    }

    @Test
    void classのoverrideから明示super委譲した親default本体を保持する() {
        assertThat(d3t(DefaultEntry.class, DefaultPort.class, SuperDefaultBridge.class)).hasSize(1);
    }

    @Test
    void 同一行の明示super委譲と通常interface呼出しは両本体を保持する() {
        assertThat(d3t(MixedDefaultBridge.class, DefaultPort.class))
            .hasSize(2)
            .anyMatch(v -> v.contains(TransitiveProxyRepository.class.getName()))
            .anyMatch(v -> v.contains(InheritedProxyRepository.class.getName()));
    }

    @Test
    void primitiveと配列のsignatureでも明示super本体を保持する() {
        assertThat(d3t(DescriptorDefaultBridge.class, DescriptorDefaultPort.class)).hasSize(1);
    }

    @ParameterizedTest(name = "D-3 合成TX [{index}] {0}")
    @MethodSource("composedEntries")
    void 合成TXをD3が検出する(Class<?> entry) {
        assertThat(detect(CrossDomainTransactionalArchTest.notDependOnOtherDomainRepositories(),
            imported(entry))).isNotEmpty();
    }

    @ParameterizedTest(name = "D-3T 合成TX [{index}] {0}")
    @MethodSource("composedEntries")
    void 合成TXをD3Tが検出する(Class<?> entry) {
        assertThat(d3t(entry)).hasSize(1);
    }

    private static List<Class<?>> composedEntries() {
        return List.of(ComposedMethodEntry.class, NestedMethodEntry.class,
            ComposedClassEntry.class, NestedClassEntry.class);
    }
    @Test
    void 非TXlistener自身は入口にせず呼出先の越境TXだけを検出する() {
        JavaClasses candidates = imported(NonTransactionalListener.class,
            SameDomainWriter.class, CrossDomainWriter.class);
        assertThat(detect(CrossDomainTransactionalTransitiveArchTest.notReachOtherDomainRepositories(),
            candidates)).singleElement()
            .satisfies(v -> assertThat(v).contains(CrossDomainWriter.class.getName() + ".execute()")
                .doesNotContain(NonTransactionalListener.class.getName()));
        assertThat(detect(CrossDomainTransactionalArchTest.notDependOnOtherDomainRepositories(),
            imported(NonTransactionalListener.class))).isEmpty();
    }

    private List<String> d3t(Class<?> entry, Class<?>... implementations) {
        List<Class<?>> candidates = new ArrayList<>(List.of(entry));
        candidates.addAll(Arrays.asList(implementations));
        return detect(CrossDomainTransactionalTransitiveArchTest.notReachOtherDomainRepositories(),
            imported(candidates.toArray(Class<?>[]::new)));
    }

    private JavaClasses imported(Class<?>... candidates) {
        List<Class<?>> types = new ArrayList<>(Arrays.asList(candidates));
        types.addAll(List.of(Port.class, ChildPort.class, AbstractBridge.class, ExcludedImplementationReference.class,
            ComposedTx.class, NestedTx.class, PlainListener.class, CycleA.class, CycleB.class,
            TransitiveProxyRepository.class, TransitiveResidentRepository.class, InheritedProxyRepository.class));
        return new ClassFileImporter().importClasses(types);
    }

    private List<String> detect(ArchCondition<JavaClass> condition, JavaClasses candidates) {
        return classes().should(condition).evaluate(candidates).getFailureReport().getDetails();
    }
}
