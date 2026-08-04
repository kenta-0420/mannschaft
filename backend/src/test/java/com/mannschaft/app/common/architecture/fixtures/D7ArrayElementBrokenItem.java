package com.mannschaft.app.common.architecture.fixtures;

/**
 * D-7 番人の<b>配列閉包</b>証明用 fixture: {@link D7RootRequest} の
 * <b>配列フィールド {@code D7ArrayElementBrokenItem[]}</b> の要素型としてのみ到達する壊れた DTO。
 *
 * <p>閉包が配列型をそのまま捨てると（{@code isArray()} で除外すると）要素型に届かず取り逃す。
 * 番人は配列を要素型まで剥がしてから閉包に入れる必要がある。
 */
public class D7ArrayElementBrokenItem {

    private final String label;

    private final Integer order;

    public D7ArrayElementBrokenItem(String label) {
        this(label, null);
    }

    public D7ArrayElementBrokenItem(String label, Integer order) {
        this.label = label;
        this.order = order;
    }

    public String getLabel() {
        return label;
    }

    public Integer getOrder() {
        return order;
    }
}
