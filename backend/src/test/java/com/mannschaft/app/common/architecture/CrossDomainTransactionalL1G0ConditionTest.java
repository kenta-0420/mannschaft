package com.mannschaft.app.common.architecture;

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

import java.lang.reflect.Method;
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
        // SecondBridgeはclasspathから解決可能でも明示候補集合に含まれない。
        assertThat(d3t(InterfaceEntry.class, SingleBridge.class))
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
    void 一段多段のメソッドとクラス合成TXをD3とD3Tが検出する() {
        for (Class<?> entry : List.of(ComposedMethodEntry.class, NestedMethodEntry.class,
                ComposedClassEntry.class, NestedClassEntry.class)) {
            assertThat(detect(CrossDomainTransactionalArchTest.class,
                "notDependOnOtherDomainRepositories", imported(entry))).as(entry.getSimpleName()).isNotEmpty();
            assertThat(d3t(entry)).as(entry.getSimpleName()).hasSize(1);
        }
    }

    @Test
    void 非TXlistener自身は入口にせず呼出先の越境TXだけを検出する() {
        JavaClasses candidates = imported(NonTransactionalListener.class,
            SameDomainWriter.class, CrossDomainWriter.class);
        assertThat(detect(CrossDomainTransactionalTransitiveArchTest.class,
            "notReachOtherDomainRepositories", candidates)).singleElement()
            .satisfies(v -> assertThat(v).contains(CrossDomainWriter.class.getName() + ".execute()")
                .doesNotContain(NonTransactionalListener.class.getName()));
        assertThat(detect(CrossDomainTransactionalArchTest.class,
            "notDependOnOtherDomainRepositories", imported(NonTransactionalListener.class))).isEmpty();
    }

    private List<String> d3t(Class<?> entry, Class<?>... implementations) {
        List<Class<?>> candidates = new ArrayList<>(List.of(entry));
        candidates.addAll(Arrays.asList(implementations));
        return detect(CrossDomainTransactionalTransitiveArchTest.class,
            "notReachOtherDomainRepositories", imported(candidates.toArray(Class<?>[]::new)));
    }

    private JavaClasses imported(Class<?>... candidates) {
        List<Class<?>> types = new ArrayList<>(Arrays.asList(candidates));
        types.addAll(List.of(Port.class, ChildPort.class, AbstractBridge.class,
            ComposedTx.class, NestedTx.class, PlainListener.class, CycleA.class, CycleB.class,
            TransitiveProxyRepository.class, TransitiveResidentRepository.class));
        return new ClassFileImporter().importClasses(types);
    }

    @SuppressWarnings("unchecked")
    private List<String> detect(Class<?> guard, String factoryName, JavaClasses candidates) {
        // 試練段階は既存private factoryを使い、未改修ロジックの実assertion失敗を観測する。
        try {
            Method factory = guard.getDeclaredMethod(factoryName);
            factory.setAccessible(true);
            ArchCondition<JavaClass> condition = (ArchCondition<JavaClass>) factory.invoke(null);
            return classes().should(condition).evaluate(candidates).getFailureReport().getDetails();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("既存番人条件を取得できない", ex);
        }
    }
}
