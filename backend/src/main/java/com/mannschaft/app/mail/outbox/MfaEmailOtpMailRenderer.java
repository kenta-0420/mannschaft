package com.mannschaft.app.mail.outbox;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@link EmailTemplateKind#MFA_EMAIL_OTP}（2FA メールOTP）の件名・本文を組み立てる（CMP-261012-0910 AC-26・AC-50）。
 *
 * <p>Worker（{@link EmailOutboxServiceImpl#processOne}）が payload の構造化変数から描画する。
 * 呼び出し側（auth）は HTML を作らず、{@code code} / {@code purpose} / {@code expiresInMinutes} だけを渡す。</p>
 *
 * <h2>本文の契約（AC-26）</h2>
 * <ul>
 *   <li>日本語のみ（裁可事項 9: OTP メールは ja 固定。locale は見ない）</li>
 *   <li>コード・有効期限・「心当たりが無ければパスワードを変更」の案内を含む</li>
 *   <li>ログインリンク・URL を一切含めない（「メール内のリンクから入力させる」フィッシングの型を作らない）</li>
 *   <li>件名にコードを載せない（端末の通知プレビュー・メール一覧での露出を防ぐ）</li>
 * </ul>
 */
final class MfaEmailOtpMailRenderer {

    private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");
    private static final int DEFAULT_EXPIRES_MINUTES = 10;

    private MfaEmailOtpMailRenderer() {
    }

    /**
     * 件名・本文を描画する。
     *
     * @param vars payload（{@code code} 必須・6 桁。{@code purpose} は LOGIN / SETUP / REAUTH。{@code expiresInMinutes}）
     * @return 件名と HTML 本文
     * @throws EmailOutboxValidationException コードが 6 桁でない（payload の破損）
     */
    static EmailOutboxServiceImpl.RenderedEmail render(Map<String, String> vars) {
        String code = vars.get("code");
        if (code == null || !SIX_DIGITS.matcher(code).matches()) {
            // コード値そのものは例外文・ログに載せない（AC-26）
            throw new EmailOutboxValidationException("EMAIL_OUTBOX_002", "MFA_EMAIL_OTP には 6 桁の code が必要");
        }
        int minutes = parseMinutes(vars.get("expiresInMinutes"));
        Purpose purpose = Purpose.of(vars.get("purpose"));

        String html = "<!DOCTYPE html><html lang=\"ja\"><head><meta charset=\"UTF-8\"></head><body>"
                + "<p>Mannschaft をご利用いただきありがとうございます。</p>"
                + "<p>" + purpose.lead + "の確認コードは次のとおりです。画面に入力してください。</p>"
                + "<p style=\"font-size:24px;font-weight:bold;letter-spacing:4px;\">" + code + "</p>"
                + "<p>このコードの有効期限は" + minutes + "分です。期限を過ぎた場合は、画面からコードを再送してください。</p>"
                + "<p>このコードは誰にも教えないでください。Mannschaft の運営者がメールや電話でコードをお尋ねすることはありません。</p>"
                + "<p>このメールにお心当たりがない場合は、第三者があなたのパスワードを使って操作しようとしている可能性があります。"
                + "速やかにパスワードを変更してください。</p>"
                + "<p>このメールは送信専用です。</p>"
                + "</body></html>";
        return new EmailOutboxServiceImpl.RenderedEmail(purpose.subject, html);
    }

    private static int parseMinutes(String raw) {
        if (raw == null) {
            return DEFAULT_EXPIRES_MINUTES;
        }
        try {
            int n = Integer.parseInt(raw.trim());
            return n > 0 ? n : DEFAULT_EXPIRES_MINUTES;
        } catch (NumberFormatException e) {
            return DEFAULT_EXPIRES_MINUTES;
        }
    }

    /** 用途ごとの件名と導入文。 */
    private enum Purpose {
        LOGIN("【Mannschaft】ログインの確認コード", "ログイン"),
        SETUP("【Mannschaft】2段階認証の設定の確認コード", "メールによる2段階認証の設定"),
        REAUTH("【Mannschaft】本人確認の確認コード", "2段階認証の設定変更に伴う本人確認"),
        /** 用途が読めない payload（旧形式等）。用途を偽らない汎用文面にする。 */
        UNKNOWN("【Mannschaft】確認コード", "ご本人確認");

        private final String subject;
        private final String lead;

        Purpose(String subject, String lead) {
            this.subject = subject;
            this.lead = lead;
        }

        static Purpose of(String raw) {
            if (raw != null) {
                for (Purpose p : values()) {
                    if (p != UNKNOWN && p.name().equals(raw)) {
                        return p;
                    }
                }
            }
            return UNKNOWN;
        }
    }
}
