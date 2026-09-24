package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.featuregate.RequireFeature;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 番人: {@code @RequireFeature} を interface（型またはメソッド）へ付与してはならない
 * （Gate 基盤工事③ / Codex 検分指摘①）。
 *
 * <h2>なぜ番人が要るのか</h2>
 * <p>{@link com.mannschaft.app.common.featuregate.FeatureGateAspect} の pointcut は
 * {@code @annotation(...)} / {@code @within(...)} / {@code execution(@RequireFeature * *(..))}
 * の3形を併用している。しかし Spring AOP の pointcut マッチングは内部で
 * {@code ClassUtils.getMostSpecificMethod(method, targetClass)} により実装クラス側の
 * オーバーライドメソッドへ解決してから注釈の有無を判定するため、
 * {@code @RequireFeature} をインターフェース（型またはメソッド）にのみ付与すると、
 * <b>JDK 動的プロキシ・CGLIB のどちらでも pointcut が一致せず Aspect が一切発火しない
 * ＝フラグ無効でも本体が実行されてしまう</b>（{@code FeatureGateAspectTest} の
 * AC-13a〜d で実測固定済み。pointcut 側の是正では閉じられない構造的な限界である）。</p>
 *
 * <p>つまりこの迂回を根治できるのは、コード側の対症療法ではなく
 * <b>「@RequireFeature をインターフェースへ付与すること自体を発生させない」</b>という
 * 構造的な禁止だけである。よって<b>付与位置の規約自体を「実装クラス（またはその public
 * メソッド）にのみ許可」に固定</b>し、CI で機械的に拒否する。</p>
 */
@DisplayName("番人: @RequireFeature はインターフェースへ付与禁止（Gate基盤工事③ 検分指摘①）")
class RequireFeatureInterfaceGuardTest {

    private static JavaClasses importedClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.mannschaft.app");
    }

    /**
     * 判定コア（純関数）。実ファイル走査と自己検証テストが同一コアを通ることで、
     * 判定ロジックが壊れて空虚 green になる事故を防ぐ。
     */
    static List<String> analyze(Iterable<JavaClass> classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass clazz : classes) {
            if (!clazz.isInterface()) {
                continue;
            }
            if (clazz.isAnnotatedWith(RequireFeature.class)) {
                violations.add(clazz.getFullName()
                        + " — インターフェース型に @RequireFeature が付与されている"
                        + "（実装クラスへは継承されないため、実装側のみに付け直すこと）");
            }
            for (JavaMethod method : clazz.getMethods()) {
                if (method.isAnnotatedWith(RequireFeature.class)) {
                    violations.add(clazz.getFullName() + "#" + method.getName()
                            + " — インターフェースのメソッドに @RequireFeature が付与されている"
                            + "（実装クラスの対応メソッドに付け直すこと）");
                }
            }
        }
        return violations;
    }

    @Test
    @DisplayName("本番コードの interface に @RequireFeature が付与されていないこと")
    void requireFeatureはinterfaceに付与されていない() {
        List<String> violations = analyze(importedClasses());

        assertThat(violations)
                .as("@RequireFeature をインターフェースへ付与してはならない。実装クラス側へ付け直すこと。\n"
                        + violations)
                .isEmpty();
    }

    @Test
    @DisplayName("陽性対照: インターフェース型付与を合成入力で検出できる（判定ロジック自己検証）")
    void 陽性対照_インターフェース型付与を検出する() {
        // 合成入力はテストソース配下にあるため DO_NOT_INCLUDE_TESTS フィルタは使わない
        // （使うとこのクラス自身が除外され、空虚 green になる）。
        JavaClasses classes = new ClassFileImporter()
                .importClasses(SyntheticInterfaceAnnotated.class);

        List<String> violations = analyze(classes);

        assertThat(violations)
                .as("合成入力: " + SyntheticInterfaceAnnotated.class.getName()
                        + " はインターフェース型に @RequireFeature を付与している合成の陽性対照だが検出できなかった")
                .anySatisfy(v -> assertThat(v).contains(SyntheticInterfaceAnnotated.class.getSimpleName()));
    }

    @Test
    @DisplayName("陽性対照: インターフェースのメソッド付与を合成入力で検出できる（判定ロジック自己検証）")
    void 陽性対照_インターフェースメソッド付与を検出する() {
        JavaClasses classes = new ClassFileImporter()
                .importClasses(SyntheticInterfaceMethodAnnotated.class);

        List<String> violations = analyze(classes);

        assertThat(violations)
                .as("合成入力: " + SyntheticInterfaceMethodAnnotated.class.getName()
                        + " はインターフェースのメソッドに @RequireFeature を付与している合成の陽性対照だが検出できなかった")
                .anySatisfy(v -> assertThat(v).contains(SyntheticInterfaceMethodAnnotated.class.getSimpleName()));
    }

    /** 合成の陽性対照（型レベル）。この番人テストのパッケージにのみ存在し、本番コードには影響しない。 */
    @RequireFeature("FEATURE_SYNTHETIC_GUARD_POSITIVE_CONTROL")
    interface SyntheticInterfaceAnnotated {
        String doSomething();
    }

    /** 合成の陽性対照（メソッドレベル）。 */
    interface SyntheticInterfaceMethodAnnotated {
        @RequireFeature("FEATURE_SYNTHETIC_GUARD_POSITIVE_CONTROL")
        String doSomething();
    }
}
