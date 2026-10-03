package com.mannschaft.app.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mannschaft.app.MannschaftApplication;
import org.junit.jupiter.api.Tag;
import com.mannschaft.app.common.architecture.fixtures.DummyPlainService;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 本番クラス共有ホルダ {@link ProductionClasses} の受け入れ試験（CMP-261002-1606）。
 *
 * <p>AC-1/AC-3 は取り込み関数を差し込める {@link ProductionClasses.Memoizer} で検証し、
 * 本番全体の取り込みを伴うのは AC-2 だけにする（取り込みは {@link ProductionClasses#get()} 経由の1回）。
 */
@DisplayName("本番クラス共有ホルダ（CMP-261002-1606）")
@Tag(ArchUnitTestTag.ARCHUNIT)
class ProductionClassesTest {

    /** 本番に実在する代表的な Service（shift の tx 本体）。 */
    private static final String KNOWN_SERVICE = "com.mannschaft.app.shift.service.ShiftSwapService";

    @Test
    @DisplayName("AC-1: get() を2回呼ぶと同一インスタンスが返る")
    void AC1_getを2回呼ぶと同一インスタンスが返る() {
        JavaClasses first = ProductionClasses.get();
        JavaClasses second = ProductionClasses.get();

        assertThat(second).isSameAs(first);
    }

    @Test
    @DisplayName("AC-1: 取り込み関数は何度 get() しても1回しか呼ばれない")
    void AC1_取り込み関数は1回しか呼ばれない() {
        AtomicInteger calls = new AtomicInteger();
        ProductionClasses.Memoizer memoizer = new ProductionClasses.Memoizer(() -> {
            calls.incrementAndGet();
            return new ClassFileImporter().importClasses(MannschaftApplication.class);
        });

        JavaClasses first = memoizer.get();
        JavaClasses second = memoizer.get();
        JavaClasses third = memoizer.get();

        assertThat(second).isSameAs(first);
        assertThat(third).isSameAs(first);
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("AC-2: 本番クラスを含み、fixture とテストクラスを含まない")
    void AC2_本番クラスを含みfixtureとテストクラスを含まない() {
        JavaClasses classes = ProductionClasses.get();

        assertThat(classes).isNotEmpty();
        assertThat(classes.contain(MannschaftApplication.class.getName()))
                .as("Application クラスを含む").isTrue();
        assertThat(classes.contain(KNOWN_SERVICE))
                .as("既知の Service %s を含む", KNOWN_SERVICE).isTrue();

        assertThat(classes.contain(DummyPlainService.class.getName()))
                .as("テスト fixture（..architecture.fixtures..）を含まない").isFalse();
        assertThat(classes.contain(ProductionClassesTest.class.getName()))
                .as("テストクラスを含まない").isFalse();
        List<String> fixtureOrTestClasses = classes.stream()
                .filter(c -> c.getName().contains(".architecture.fixtures.") || isFromTestOutput(c))
                .map(JavaClass::getName)
                .toList();
        assertThat(fixtureOrTestClasses).as("fixture・テストクラスが混入していない").isEmpty();
    }

    /** クラスファイルの出所がテスト出力（Gradle の classes/java/test）か。 */
    private static boolean isFromTestOutput(JavaClass javaClass) {
        return javaClass.getSource()
                .map(source -> source.getUri().toString().replace('\\', '/').contains("/classes/java/test/"))
                .orElse(false);
    }

    @Test
    @DisplayName("AC-3: 取り込みが例外を投げたら初回はその例外が同一インスタンスで伝わる")
    void AC3_取り込み失敗は初回にそのまま伝わる() {
        IllegalStateException failure = new IllegalStateException("取り込み失敗の模擬",
                new OutOfMemoryError("模擬 OOM"));
        ProductionClasses.Memoizer memoizer = new ProductionClasses.Memoizer(() -> {
            throw failure;
        });

        assertThatThrownBy(memoizer::get)
                .isSameAs(failure)
                .hasCauseInstanceOf(OutOfMemoryError.class);
    }

    @Test
    @DisplayName("AC-3: 失敗後の再呼び出しは再試行せず、初回の例外を cause に持つ ISE を再送する")
    void AC3_失敗後の再呼び出しは同じ失敗を再送し正常値を返さない() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException failure = new RuntimeException("取り込み失敗の模擬");
        ProductionClasses.Memoizer memoizer = new ProductionClasses.Memoizer(() -> {
            calls.incrementAndGet();
            throw failure;
        });

        assertThatThrownBy(memoizer::get).isSameAs(failure);
        assertThatThrownBy(memoizer::get)
                .isInstanceOf(IllegalStateException.class)
                .hasCauseReference(failure);
        assertThatThrownBy(memoizer::get)
                .isInstanceOf(IllegalStateException.class)
                .hasCauseReference(failure);
        assertThat(calls).as("再試行しない").hasValue(1);
    }

    @Test
    @DisplayName("AC-3: 取り込み結果が空なら正常値として返さず、以後も失敗を再送する")
    void AC3_空の取り込み結果は正常値として返さない() {
        AtomicInteger calls = new AtomicInteger();
        ProductionClasses.Memoizer memoizer = new ProductionClasses.Memoizer(() -> {
            calls.incrementAndGet();
            return new ClassFileImporter().importClasses(List.of());
        });

        assertThatThrownBy(memoizer::get).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(memoizer::get).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("AC-3: 取り込み結果が null なら正常値として返さない")
    void AC3_nullの取り込み結果は正常値として返さない() {
        ProductionClasses.Memoizer memoizer = new ProductionClasses.Memoizer(() -> null);

        assertThatThrownBy(memoizer::get).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(memoizer::get).isInstanceOf(IllegalStateException.class);
    }
}
