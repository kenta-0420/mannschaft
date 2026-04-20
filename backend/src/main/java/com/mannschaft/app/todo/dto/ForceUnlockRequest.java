package com.mannschaft.app.todo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * マイルストーン強制アンロックリクエスト（F02.7）。
 *
 * <p>ADMIN のみ実行可能。理由（reason）は監査ログに記録され、GDPR 保持ポリシーに従い 1 年で削除される。</p>
 */
public record ForceUnlockRequest(
        @NotBlank
        @Size(max = 100, message = "reason は 100 文字以内で入力してください")
        String reason
) {}
