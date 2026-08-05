package com.mannschaft.app.common.architecture.fixtures;

/**
 * D-1 番人（{@code CrossDomainEntityImportArchTest}）の合成クラス除外を検証するための
 * <b>試練専用フィクスチャ</b>。
 *
 * <p>本クラスは production コードではなく {@code src/test/java} 配下（D-1 番人の
 * {@code @AnalyzeClasses(importOptions = ImportOption.DoNotIncludeTests.class)} により
 * 解析対象外）に置く。enum に対する網羅 {@code switch} 文を書くことで、javac に
 * 合成クラス {@code SyntheticSwitchFixture$1}（{@code $SwitchMap} を保持する）を
 * 確実に生成させる。
 *
 * <p>{@link com.mannschaft.app.common.architecture.CrossDomainEntityImportSyntheticClassExclusionTest}
 * がこのクラスをコンパイル後の実物のクラスファイルとして {@code ClassFileImporter} で読み込み、
 * 合成クラス判定述語 {@code SyntheticClasses#isSynthetic} の挙動を検証する。
 */
public final class SyntheticSwitchFixture {

    /** 試練専用の内部 enum。 */
    public enum Sample {
        A, B, C
    }

    /**
     * enum に対する網羅 switch 文。javac がこの記述から合成クラス
     * {@code SyntheticSwitchFixture$1} を生成する。
     */
    public static int classify(Sample sample) {
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
