package com.mannschaft.app.auth.dto;

import com.mannschaft.app.auth.MfaVerificationMethod;
import com.mannschaft.app.auth.TwoFactorMethod;
import lombok.Getter;

import java.util.List;

/**
 * MFA認証が必要な場合のレスポンス。
 *
 * <p>CMP-261012-0910 AC-27: {@link #primaryMethod}（ユーザーの主手段）と {@link #availableMethods}
 * （このログインで提示できる要素）を追加した。既存の {@code mfaRequired} / {@code mfaSessionToken} は互換維持。</p>
 */
@Getter
public class MfaRequiredResponse {

    private final boolean mfaRequired;
    private final String mfaSessionToken;
    /** ユーザーの 2FA 主手段（TOTP / EMAIL）。 */
    private final TwoFactorMethod primaryMethod;
    /** このログインで提示できる要素。メール未登録ユーザーには EMAIL を含めない（AC-24）。 */
    private final List<MfaVerificationMethod> availableMethods;

    /**
     * 旧コンストラクタ（方式情報なし）。CMP-261012-0910 骨格段階の互換用。
     */
    public MfaRequiredResponse(String mfaSessionToken) {
        this(mfaSessionToken, null, List.of());
    }

    public MfaRequiredResponse(String mfaSessionToken, TwoFactorMethod primaryMethod,
                               List<MfaVerificationMethod> availableMethods) {
        this.mfaRequired = true;
        this.mfaSessionToken = mfaSessionToken;
        this.primaryMethod = primaryMethod;
        this.availableMethods = availableMethods;
    }
}
