package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * D-1 番人（{@link CrossDomainEntityImportArchTest}）が javac の合成クラス
 * （enum の網羅 {@code switch} から生まれる {@code Foo$1}）を新規違反として
 * 誤検出しないことの試練。
 *
 * <h2>試練の設計方針</h2>
 * <p>D-1 番人自体は {@code FreezingArchRule} で既存違反を凍結ストアに記録する構造上、
 * production コードにドメイン越境 enum への網羅 switch を新規に書き込んで番人を
 * fail/pass させる形の試練は書けない
 * （凍結ストア・{@link ArchUnitFreezeStoreIntegrityTest} の期待行数まで動かしてしまい、
 * 「番人が誤っていたから実装を歪めた」の逆＝「試練のために番人の台帳を書き換える」
 * という別の事故を招く）。
 *
 * <p>そこで<b>除外述語 {@link SyntheticClasses#isSynthetic} そのもの</b>を、
 * D-1 の解析対象外（{@code ImportOption.DoNotIncludeTests}）である
 * {@code src/test/java} 配下の試練専用フィクスチャ（{@code fixtures} パッケージ）で
 * 実際に javac にコンパイルさせ、生じた実物のクラスファイルを
 * {@link ClassFileImporter} で読み込んで直接検証する。
 *
 * <ul>
 *   <li><b>AC-A1</b>: 合成クラス（{@code SyntheticSwitchFixture$1}）は
 *       {@code isSynthetic} で {@code true} と判定される。</li>
 *   <li><b>AC-A2</b>: 合成クラスを生んだ外側クラス自身（{@code SyntheticSwitchFixture}）は
 *       {@code isSynthetic} で {@code false} と判定される（除外が本体まで
 *       見逃さないことの確認）。</li>
 *   <li><b>AC-A3</b>: Lombok の {@code @SuperBuilder} が生成する実在の production
 *       ネストクラス（{@code ConfirmableNotificationEntity$ConfirmableNotificationEntityBuilder}、
 *       凍結ストアに既存違反として現存する）は {@code isSynthetic} で {@code false} と
 *       判定される。名前ベースの雑な条件（{@code $} を含むかどうか）を採らなかったことの
 *       直接的な裏付け。</li>
 * </ul>
 *
 * <p>フィクスチャがビルド成果物として存在しない場合（テストソースのコンパイルが
 * 走っていない等）は {@code skip} ではなく明示的に {@link org.junit.jupiter.api.Assertions#fail}
 * させる（「見つからなければ skip」は偽の緑になるため禁止 — CLAUDE.md 障害対応の原則）。
 */
class CrossDomainEntityImportSyntheticClassExclusionTest {

    private static final String FIXTURES_PACKAGE =
        "com.mannschaft.app.common.architecture.fixtures";

    /** Lombok {@code @SuperBuilder} の実物のネストクラスを持つ既存 production クラス。 */
    private static final String LOMBOK_BUILDER_OWNER =
        "com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity";

    private static JavaClasses importFixtures() {
        JavaClasses classes = new ClassFileImporter().importPackages(FIXTURES_PACKAGE);
        if (classes.isEmpty()) {
            fail("フィクスチャパッケージ " + FIXTURES_PACKAGE + " のクラスが1件も見つからなかった。"
                + "テストソースのコンパイルが走っていない可能性がある（skipではなくfailとする）。");
        }
        return classes;
    }

    private static JavaClass findByFullNameSuffix(JavaClasses classes, String suffix) {
        return classes.stream()
            .filter(c -> c.getFullName().endsWith(suffix))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "フルネームが \"" + suffix + "\" で終わるクラスが見つからない（対象: "
                    + classes.size() + "件）"));
    }

    @Test
    @DisplayName("AC-A1: enumの網羅switchが生成する合成クラス($1)はisSyntheticでtrue判定される")
    void syntheticSwitchMapClassIsDetected() {
        JavaClasses classes = importFixtures();
        JavaClass synthetic = findByFullNameSuffix(classes, "SyntheticSwitchFixture$1");

        assertTrue(SyntheticClasses.isSynthetic(synthetic),
            "javacが生成する$SwitchMap保持クラスはACC_SYNTHETICフラグを持つはず: "
                + synthetic.getFullName());
    }

    @Test
    @DisplayName("AC-A2: 合成クラスを生んだ外側クラス自身はisSyntheticでfalse判定される(除外が本体まで見逃さない)")
    void enclosingClassOfSyntheticIsNotExcluded() {
        JavaClasses classes = importFixtures();
        JavaClass outer = classes.stream()
            .filter(c -> c.getFullName().equals(FIXTURES_PACKAGE + ".SyntheticSwitchFixture"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("SyntheticSwitchFixture 本体が見つからない"));

        assertFalse(SyntheticClasses.isSynthetic(outer),
            "合成クラスを生んだ外側クラス自身は合成ではない。除外が本体の違反まで"
                + "見逃してはならない: " + outer.getFullName());
    }

    @Test
    @DisplayName("AC-A3: Lombok @SuperBuilderが生成する実在ネストクラスはisSyntheticでfalse判定される"
        + "(既存負債を$含みで免罪しない)")
    void lombokBuilderNestedClassIsNotExcluded() {
        JavaClasses classes = new ClassFileImporter()
            .importPackagesOf(getClassOrFail(LOMBOK_BUILDER_OWNER));
        JavaClass builder = findByFullNameSuffix(classes,
            "ConfirmableNotificationEntity$ConfirmableNotificationEntityBuilder");

        assertFalse(SyntheticClasses.isSynthetic(builder),
            "Lombokの@SuperBuilderが生成するネストクラスはSYNTHETIC修飾子を持たない。"
                + "'$'を含むという理由だけで除外してはならない（凍結ストアの既存Lombok"
                + "違反を静かに免罪することになるため）: " + builder.getFullName());
    }

    private static Class<?> getClassOrFail(String fqcn) {
        try {
            return Class.forName(fqcn);
        } catch (ClassNotFoundException e) {
            fail(fqcn + " がクラスパス上に見つからない: " + e.getMessage());
            throw new AssertionError("unreachable");
        }
    }
}
