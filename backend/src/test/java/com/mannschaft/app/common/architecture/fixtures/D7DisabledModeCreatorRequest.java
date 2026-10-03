package com.mannschaft.app.common.architecture.fixtures;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * D-7 番人の偽陰性 fixture: <b>唯一のコンストラクタ</b>に
 * {@code @JsonCreator(mode = Mode.DISABLED)} が付いた DTO。
 *
 * <p>{@code mode = DISABLED} は「このコンストラクタを creator として<b>使うな</b>」という
 * 明示的な打ち消しである。番人は「コンストラクタが 1 本なら {@code -parameters} で暗黙 creator に
 * なる」という理由で合格させているが、その暗黙解決はここで<b>明示的に断たれている</b>。
 * 結果 creator が 1 本も無くなり、{@code InvalidDefinitionException} で常時 500 になる。
 * 注釈の<b>存在</b>だけを見る判定はこれを素通りさせる。
 *
 * <p><b>実測（2026-08-05）</b>: コンストラクタが 2 本あって片方だけが {@code DISABLED} の場合は、
 * 残る 1 本が暗黙 creator として採用され<b>正常に往復する</b>。よって番人は「DISABLED が付いている」
 * ことではなく「<b>打ち消し後に残る候補が何本か</b>」で判定する
 * （非検出側の固定は {@link D7DisabledPlusFallbackCreatorRequest}）。実測固定は
 * {@link com.mannschaft.app.common.architecture.JsonRequestBodyCreatorRuntimeProofTest}。
 */
public class D7DisabledModeCreatorRequest {

    private final Long categoryId;

    private final String title;

    /** 唯一のコンストラクタだが creator としての採用を明示的に打ち消している。 */
    @JsonCreator(mode = JsonCreator.Mode.DISABLED)
    public D7DisabledModeCreatorRequest(
            @JsonProperty("categoryId") Long categoryId,
            @JsonProperty("title") String title) {
        this.categoryId = categoryId;
        this.title = title;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getTitle() {
        return title;
    }
}
