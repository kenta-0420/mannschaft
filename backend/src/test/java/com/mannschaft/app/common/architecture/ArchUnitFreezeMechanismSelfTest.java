package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import com.tngtech.archunit.library.freeze.TextFileBasedViolationStore;
import com.tngtech.archunit.library.freeze.ViolationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 本来のFreezingArchRuleとテキストストアを所有TempDir内だけで試す。 */
class ArchUnitFreezeMechanismSelfTest {
    @TempDir
    Path storeDir;

    @Test
    void 正当な解消は自動削減され期待値未更新の後段番人が拒否する() {
        assertEquals("false", ArchConfiguration.get().getProperty("freeze.refreeze"));
        AtomicReference<String> violation = new AtomicReference<>("既知の違反");
        ArchRule rule = fixtureRule(violation);
        ViolationStore store = ownedStore();
        FreezingArchRule frozen = FreezingArchRule.freeze(rule).persistIn(store);
        JavaClasses subjects = new ClassFileImporter().importClasses(ProbeSubject.class);

        assertFalse(frozen.evaluate(subjects).hasViolation());
        assertEquals(List.of("既知の違反"), store.getViolations(rule));
        violation.set(null);
        assertFalse(frozen.evaluate(subjects).hasViolation());
        assertEquals(List.of(), store.getViolations(rule));
        assertThrows(AssertionError.class, () -> ArchUnitFreezeStoreIntegrityTest.verifyStoreCounts(storeDir,
            List.of(new ArchUnitFreezeStoreIntegrityTest.FrozenStoreExpectation(rule.getDescription(), "fixture", 1))));
    }

    @Test
    void 異なる新規違反は失敗しストアへ追加凍結されない() {
        assertEquals("false", ArchConfiguration.get().getProperty("freeze.refreeze"));
        AtomicReference<String> violation = new AtomicReference<>("既知の違反");
        ArchRule rule = fixtureRule(violation);
        ViolationStore store = ownedStore();
        FreezingArchRule frozen = FreezingArchRule.freeze(rule).persistIn(store);
        JavaClasses subjects = new ClassFileImporter().importClasses(ProbeSubject.class);

        assertFalse(frozen.evaluate(subjects).hasViolation());
        violation.set("既知の違反とは異なる新規違反");
        assertTrue(frozen.evaluate(subjects).hasViolation());
        assertThrows(AssertionError.class, () -> frozen.check(subjects));
        assertFalse(store.getViolations(rule).contains("既知の違反とは異なる新規違反"));
    }

    private ArchRule fixtureRule(AtomicReference<String> violation) {
        return classes().should(new ArchCondition<JavaClass>("所有試練の条件") {
            @Override
            public void check(JavaClass subject, ConditionEvents events) {
                if (violation.get() != null) {
                    events.add(SimpleConditionEvent.violated(subject, violation.get()));
                }
            }
        }).as("所有試練の凍結ルール");
    }

    private ViolationStore ownedStore() {
        TextFileBasedViolationStore delegate = new TextFileBasedViolationStore(description -> "fixture");
        return new ViolationStore() {
            @Override
            public void initialize(Properties ignoredGlobalProperties) {
                Properties ownedProperties = new Properties();
                ownedProperties.setProperty("default.path", storeDir.toString());
                ownedProperties.setProperty("default.allowStoreCreation", "true");
                ownedProperties.setProperty("default.allowStoreUpdate", "true");
                delegate.initialize(ownedProperties);
            }

            @Override
            public boolean contains(ArchRule rule) {
                return delegate.contains(rule);
            }

            @Override
            public void save(ArchRule rule, List<String> violations) {
                delegate.save(rule, violations);
            }

            @Override
            public List<String> getViolations(ArchRule rule) {
                return delegate.getViolations(rule);
            }
        };
    }

    static class ProbeSubject {
    }
}
