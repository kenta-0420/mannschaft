package com.mannschaft.app.common.architecture.fixtures;

/**
 * {@link SyntheticSwitchFixture} が合成クラス {@code SyntheticSwitchFixture$1}
 * （{@code $SwitchMap} を保持するクラス）を生成させるための試練専用 enum。
 *
 * <p>javac の合成クラス生成は「enum に対する網羅 switch 文を書く」だけでは起きない。
 * 実測（javac 21.0.10）では、switch 対象の enum が <b>switch 文を書くクラス自身の
 * 入れ子ではなく、別の top-level クラスであること</b>が生成条件になる。enum を
 * switch 元クラスの入れ子として宣言すると javac は {@code ordinal()} を呼んで
 * {@code tableswitch} を直接叩くだけで、合成クラスは一切生成されない。
 * そのため本 enum は {@link SyntheticSwitchFixture} から独立した top-level ファイルとして
 * 切り出している。
 */
public enum SyntheticSampleEnum {
    A, B, C
}
