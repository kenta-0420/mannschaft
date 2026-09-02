package com.mannschaft.app.provisioning.service;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 柱②-2: 招待メールアドレスの正規化（NFC 正規化 + lowercase + trim）。
 *
 * <p>招待発行時に保存する {@code invite_email} と、承諾時のログインユーザーの検証済み
 * メールアドレスを比較する際、Unicode 正規化形式（NFC/NFD）や大文字小文字の違いだけで
 * 同一のメールアドレスが不一致と誤判定されないよう、比較直前に必ず本メソッドを通す。</p>
 */
@Component
public class ProvisioningEmailNormalizer {

    /**
     * メールアドレスを NFC 正規化 + lowercase + trim した比較用表現へ変換する。
     *
     * @param email 生のメールアドレス（null 不可）
     * @return 比較用に正規化された表現
     */
    public String normalize(String email) {
        if (email == null) {
            throw new IllegalArgumentException("email must not be null");
        }
        String trimmed = email.trim();
        String nfc = Normalizer.normalize(trimmed, Normalizer.Form.NFC);
        return nfc.toLowerCase(Locale.ROOT);
    }
}
