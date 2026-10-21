package com.mannschaft.app.auth.dto;

import com.mannschaft.app.auth.TwoFactorMethod;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 2FA 設定状態（{@code GET /api/v1/auth/2fa/status}）のレスポンス（CMP-261012-0910 AC-4）。
 *
 * <p>秘密鍵・バックアップコードのハッシュは決して含めない。未設定・未有効化は
 * {@code enabled=false, method=null, backupCodesRemaining=0}。</p>
 */
@Getter
@RequiredArgsConstructor
public class TwoFactorStatusResponse {

    private final boolean enabled;
    private final TwoFactorMethod method;
    private final int backupCodesRemaining;
}
