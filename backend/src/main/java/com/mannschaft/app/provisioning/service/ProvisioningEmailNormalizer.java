package com.mannschaft.app.provisioning.service;

import org.springframework.stereotype.Component;

/**
 * 柱②-2: 招待メールアドレスの正規化（NFC 正規化 + lowercase）。
 *
 * <p>招待発行時に保存する {@code invite_email} と、承諾時のログインユーザーの検証済み
 * メールアドレスを比較する際、Unicode 正規化形式（NFC/NFD）や大文字小文字の違いだけで
 * 同一のメールアドレスが不一致と誤判定されないよう、比較直前に必ず本メソッドを通す。</p>
 *
 * <p>本 PR では試練（受け入れテスト）のみを設置する。実装は後続 PR（出陣）で行う。</p>
 */
@Component
public class ProvisioningEmailNormalizer {

    /**
     * メールアドレスを NFC 正規化 + lowercase した比較用表現へ変換する。
     *
     * @param email 生のメールアドレス
     * @return 比較用に正規化された表現
     */
    public String normalize(String email) {
        // TODO 出陣で実装: java.text.Normalizer.normalize(email, Form.NFC).toLowerCase(Locale.ROOT)
        throw new UnsupportedOperationException("ProvisioningEmailNormalizer#normalize is not implemented yet");
    }
}
