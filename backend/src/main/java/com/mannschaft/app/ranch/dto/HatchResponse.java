package com.mannschaft.app.ranch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 初回・同一キー再送は保存された出生結果、新しいキーで同名なら現在状態を返す。 */
public record HatchResponse(Kind kind, @Schema(nullable = true) HatchResult result,
                            @Schema(nullable = true) RanchState state) {
    public enum Kind { HATCH_RESULT, CURRENT_STATE }
}
