package com.mannschaft.app.common.architecture.fixtures;

import java.util.List;

/**
 * D-7 番人の<b>入れ子検出</b>証明用 fixture: 自身は合格（コンストラクタ 1 本）だが、
 * フィールドに壊れた入れ子 DTO {@link D7NestedBrokenAttachment} を持つ根 DTO。
 *
 * <p>{@code List<...>} のジェネリクス引数越しでも閉包が届くことを担保する。
 */
public class D7RootRequest {

    private final String title;

    private final List<D7NestedBrokenAttachment> attachments;

    public D7RootRequest(String title, List<D7NestedBrokenAttachment> attachments) {
        this.title = title;
        this.attachments = attachments;
    }

    public String getTitle() {
        return title;
    }

    public List<D7NestedBrokenAttachment> getAttachments() {
        return attachments;
    }
}
