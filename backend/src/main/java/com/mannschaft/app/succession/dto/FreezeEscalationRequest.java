package com.mannschaft.app.succession.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * エスカレーション凍結リクエスト DTO（F09.15 S5-B）。
 *
 * <p>弁護士介入や行政手続き等でエスカレーションの自動進行を一時停止する際に使用する。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FreezeEscalationRequest {

    /**
     * 凍結理由（必須、最大 500 文字）。
     * 例: 「弁護士が法的手続きを開始したため」。
     */
    @NotBlank(message = "凍結理由は必須です")
    @Size(max = 500, message = "凍結理由は 500 文字以内で入力してください")
    private String reason;
}
