package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.MfaVerificationMethod;
import com.mannschaft.app.auth.TwoFactorMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 2FA の方式に関する純粋な判定（CMP-261012-0910）。状態を持たない。
 */
public final class MfaMethodPolicy {

    /** システムが内部的に付与するダミーアドレスのドメイン（実在の受信箱が無い）。 */
    private static final String INTERNAL_DOMAIN_SUFFIX = ".mannschaft.internal";

    private MfaMethodPolicy() {
    }

    /**
     * メールOTP を届けられるアドレスか（殿裁定: null・空白・{@code *.mannschaft.internal} は不可。AC-24）。
     *
     * @param email 登録メールアドレス
     * @return 届けられるなら true
     */
    public static boolean isEmailDeliverable(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        return !email.trim().toLowerCase(Locale.ROOT).endsWith(INTERNAL_DOMAIN_SUFFIX);
    }

    /**
     * 2FA ログインで提示できる要素（AC-24/AC-27）。主手段を先頭に置く。
     *
     * <ul>
     *   <li>TOTP 方式: TOTP・バックアップコード・（届くアドレスがあれば）メールOTP（代替）</li>
     *   <li>EMAIL 方式: （届くアドレスがあれば）メールOTP・バックアップコード。TOTP は含めない</li>
     * </ul>
     *
     * @param primary          主手段（null は TOTP とみなす＝列追加前の行）
     * @param emailDeliverable メールOTP を届けられるか
     * @return 提示できる要素
     */
    public static List<MfaVerificationMethod> availableMethods(TwoFactorMethod primary, boolean emailDeliverable) {
        List<MfaVerificationMethod> methods = new ArrayList<>(3);
        if (primary == TwoFactorMethod.EMAIL) {
            if (emailDeliverable) {
                methods.add(MfaVerificationMethod.EMAIL);
            }
            methods.add(MfaVerificationMethod.BACKUP_CODE);
        } else {
            methods.add(MfaVerificationMethod.TOTP);
            methods.add(MfaVerificationMethod.BACKUP_CODE);
            if (emailDeliverable) {
                methods.add(MfaVerificationMethod.EMAIL);
            }
        }
        return List.copyOf(methods);
    }
}
