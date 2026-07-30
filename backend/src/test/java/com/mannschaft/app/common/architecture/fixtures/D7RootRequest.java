package com.mannschaft.app.common.architecture.fixtures;

import java.util.List;

/**
 * D-7 番人の<b>入れ子検出</b>証明用 fixture: 自身は合格（コンストラクタ 1 本）だが、
 * フィールドに壊れた入れ子 DTO を 2 通りの持ち方で保持する根 DTO。
 *
 * <ul>
 *   <li>{@code List<D7NestedBrokenAttachment>} — ジェネリクス引数越しの到達</li>
 *   <li>{@code D7ArrayElementBrokenItem[]} — <b>配列</b>越しの到達（要素型まで剥がす必要がある）</li>
 * </ul>
 */
public class D7RootRequest {

    private final String title;

    private final List<D7NestedBrokenAttachment> attachments;

    private final D7ArrayElementBrokenItem[] items;

    public D7RootRequest(String title, List<D7NestedBrokenAttachment> attachments,
                         D7ArrayElementBrokenItem[] items) {
        this.title = title;
        this.attachments = attachments;
        this.items = items;
    }

    public String getTitle() {
        return title;
    }

    public List<D7NestedBrokenAttachment> getAttachments() {
        return attachments;
    }

    public D7ArrayElementBrokenItem[] getItems() {
        return items;
    }
}
