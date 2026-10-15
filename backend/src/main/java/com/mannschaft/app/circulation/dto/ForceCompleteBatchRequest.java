package com.mannschaft.app.circulation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 一括強制完了リクエスト DTO。
 *
 * <p>Phase 11 第三陣 3-A で追加。
 * 最大 20 件まで一括で強制完了可能とする（F05.2 §4.7）。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class ForceCompleteBatchRequest {

    /**
     * 強制完了対象の文書 ID リスト（必須・最大 20 件）。
     */
    @NotNull
    @NotEmpty
    @Size(max = 20)
    private List<Long> documentIds;
}
