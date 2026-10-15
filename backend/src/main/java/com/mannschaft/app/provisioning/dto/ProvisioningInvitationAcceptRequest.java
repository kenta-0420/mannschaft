package com.mannschaft.app.provisioning.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 柱②-2: 招待の下見（preview）/ 承諾（accept）リクエスト。
 *
 * <p>トークンは URL パスへは載せず、POST ボディで渡す
 * （URL はブラウザ履歴・アクセスログ・Referer に残るため）。</p>
 *
 * @param token 平文トークン（Base64URL・43 文字。{@link com.mannschaft.app.common.token.SecretTokenVault}）
 */
public record ProvisioningInvitationAcceptRequest(
        @NotBlank String token
) {
}
