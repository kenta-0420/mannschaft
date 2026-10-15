package com.mannschaft.app.common.architecture.fixtures;

/**
 * D-7 番人の<b>入れ子検出</b>証明用 fixture: それ自体は {@code @RequestBody} 引数型ではないが、
 * バインドされる {@link D7RootRequest} の<b>フィールド型</b>として到達する壊れた DTO。
 *
 * <p>{@code chat.dto.SendMessageRequest} が {@code List<AttachmentRequest>} を持つのと同じ構図で、
 * 入れ子 DTO も同じく「no suitable creator」で親ごとデシリアライズを失敗させ 500 を招く。
 * よって番人はフィールド型の推移閉包まで検査する必要がある。
 */
public class D7NestedBrokenAttachment {

    private final String fileName;

    private final Long size;

    public D7NestedBrokenAttachment(String fileName) {
        this(fileName, null);
    }

    public D7NestedBrokenAttachment(String fileName, Long size) {
        this.fileName = fileName;
        this.size = size;
    }

    public String getFileName() {
        return fileName;
    }

    public Long getSize() {
        return size;
    }
}
