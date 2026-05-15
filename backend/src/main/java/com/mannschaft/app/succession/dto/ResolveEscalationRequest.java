package com.mannschaft.app.succession.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * エスカレーション解決リクエスト DTO（F09.15 S5-B）。
 *
 * <p>滞納が解消された（支払い完了・死亡確認・手動クローズ等）場合に
 * エスカレーションを解決済みに遷移させる。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResolveEscalationRequest {

    /**
     * 解決理由コード（必須、最大 50 文字）。
     * PAID / DEATH_CONFIRMED / MANUAL_CLOSE 等の区分コードを指定する。
     */
    @NotBlank(message = "解決理由は必須です")
    @Size(max = 50, message = "解決理由は 50 文字以内で入力してください")
    private String resolvedReason;
}
