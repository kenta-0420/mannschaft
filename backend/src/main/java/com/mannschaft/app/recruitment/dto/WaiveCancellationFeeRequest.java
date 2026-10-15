package com.mannschaft.app.recruitment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * F03.11.1 募集キャンセル料の免除リクエスト（設計書 §10.1）。
 *
 * <p>免除は金銭債権を消す不可逆な操作であり、後から「誰がなぜ消したか」を追えないまま実行させてはならない。
 * そのため理由は必須である。最大長は {@code notes VARCHAR(500)} に収まる 500 文字とする。</p>
 */
@Getter
@NoArgsConstructor
public class WaiveCancellationFeeRequest {

    /** 免除理由（必須）。 */
    @NotBlank
    @Size(max = 500)
    private String reason;
}
