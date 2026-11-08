package com.mannschaft.app.auth.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.auth.MfaVerificationMethod;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

/**
 * MFAセッション付き 2FA ログイン検証リクエスト（{@code POST /api/v1/auth/2fa/validate}）。
 *
 * <p>CMP-261012-0910: {@code {mfaSessionToken, method: TOTP|BACKUP_CODE|EMAIL, code}} へ拡張した。
 * 旧形式 {@code {mfaSessionToken, totpCode}} も互換で受ける（AC-53）:</p>
 * <ul>
 *   <li>{@code method} 欠落 → TOTP とみなす</li>
 *   <li>{@code method} が未知の値 → 400</li>
 *   <li>{@code code} と {@code totpCode} の両方指定（新旧競合）→ 400</li>
 *   <li>形式: TOTP/EMAIL は {@code ^\d{6}$}、BACKUP_CODE は {@code ^\d{8}$}。違反は 400 で試行非計上（AC-10）</li>
 * </ul>
 */
@Getter
public class ValidateTotpLoginRequest {

    @NotBlank
    private final String mfaSessionToken;

    /** 旧形式のコード欄（TOTP 6 桁）。新形式では {@link #code} を使う。 */
    private final String totpCode;

    /** 提示する要素。欠落時は TOTP。 */
    private final MfaVerificationMethod method;

    /** 新形式のコード欄。 */
    private final String code;

    /** JSON 本文からの生成（新旧両形式を受ける）。 */
    @JsonCreator
    public ValidateTotpLoginRequest(
            @JsonProperty("mfaSessionToken") String mfaSessionToken,
            @JsonProperty("totpCode") String totpCode,
            @JsonProperty("method") MfaVerificationMethod method,
            @JsonProperty("code") String code) {
        this.mfaSessionToken = mfaSessionToken;
        this.totpCode = totpCode;
        this.method = method;
        this.code = code;
    }

    /** 旧形式（{@code {mfaSessionToken, totpCode}}）のコンストラクタ。 */
    public ValidateTotpLoginRequest(String mfaSessionToken, String totpCode) {
        this(mfaSessionToken, totpCode, null, null);
    }

    /** 実効的な要素（{@code method} 欠落時は TOTP）。 */
    public MfaVerificationMethod effectiveMethod() {
        return method != null ? method : MfaVerificationMethod.TOTP;
    }

    /** 実効的なコード（新形式 {@code code} 優先、無ければ旧 {@code totpCode}）。 */
    public String effectiveCode() {
        return code != null ? code : totpCode;
    }

    /**
     * 新旧コード欄の競合が無いこと（AC-53）。両方指定は片方を黙って優先せず 400 にする。
     */
    @JsonIgnore
    @AssertTrue(message = "code と totpCode は同時に指定できません")
    public boolean isCodeFieldsExclusive() {
        return code == null || totpCode == null;
    }

    /**
     * 要素ごとのコード形式（AC-10）。TOTP / EMAIL は 6 桁、BACKUP_CODE は 8 桁の数字。null・空・違反は 400 で
     * 試行回数に計上しない（Bean Validation で Service 到達前に弾く）。新旧競合は {@link #isCodeFieldsExclusive} が報告する。
     */
    @JsonIgnore
    @AssertTrue(message = "確認コードの形式が正しくありません")
    public boolean isCodeFormatValid() {
        if (code != null && totpCode != null) {
            return true;
        }
        String c = effectiveCode();
        if (c == null) {
            return false;
        }
        return effectiveMethod() == MfaVerificationMethod.BACKUP_CODE
                ? BACKUP_CODE_PATTERN.matcher(c).matches()
                : SIX_DIGIT_PATTERN.matcher(c).matches();
    }

    private static final java.util.regex.Pattern SIX_DIGIT_PATTERN = java.util.regex.Pattern.compile("^\\d{6}$");
    private static final java.util.regex.Pattern BACKUP_CODE_PATTERN = java.util.regex.Pattern.compile("^\\d{8}$");
}
