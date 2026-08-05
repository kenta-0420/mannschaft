package com.mannschaft.app.common.architecture.fixtures;

/**
 * D-1 番人（{@code CrossDomainEntityImportArchTest}）の合成クラス除外を検証するための
 * <b>試練専用フィクスチャ</b>。
 *
 * <p>本クラスは production コードではなく {@code src/test/java} 配下（D-1 番人の
 * {@code @AnalyzeClasses(importOptions = ImportOption.DoNotIncludeTests.class)} により
 * 解析対象外）に置く。{@link SyntheticSampleEnum}（本クラスとは別の top-level enum）
 * に対する網羅 {@code switch} 文（{@code case} 形式）を書くことで、javac に
 * 合成クラス {@code SyntheticSwitchFixture$1}（{@code $SwitchMap} を保持する）を
 * 確実に生成させる。
 *
 * <p><b>合成クラスが生成される条件（javac 21.0.10 実測）</b>: switch 対象の enum が
 * 「別の top-level クラス」であること。仮に enum を本クラスの入れ子として宣言すると、
 * javac は {@code ordinal()} を呼んで {@code tableswitch} を直接叩くだけになり、
 * 合成クラスは一切生成されない（"enum への網羅 switch を書けば必ず合成クラスが出る"
 * というのは誤り）。このため試練専用 enum は {@link SyntheticSampleEnum} として
 * 別ファイルに切り出している。
 *
 * <p>{@link com.mannschaft.app.common.architecture.CrossDomainEntityImportSyntheticClassExclusionTest}
 * がこのクラスをコンパイル後の実物のクラスファイルとして {@code ClassFileImporter} で読み込み、
 * 合成クラス判定述語 {@code SyntheticClasses#isSynthetic} の挙動を検証する。
 */
public final class SyntheticSwitchFixture {

    /**
     * {@link SyntheticSampleEnum} に対する網羅 switch 文。javac がこの記述から
     * 合成クラス {@code SyntheticSwitchFixture$1} を生成する。
     */
    public static int classify(SyntheticSampleEnum sample) {
        switch (sample) {
            case A:
                return 1;
            case B:
                return 2;
            case C:
                return 3;
            default:
                return 0;
        }
    }

    private SyntheticSwitchFixture() {
    }
}
