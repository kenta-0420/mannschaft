package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.MfaEmailOtpPurpose;
import com.mannschaft.app.auth.dto.MfaEmailSendResponse;

/**
 * メールOTP の発行・照合部品（CMP-261012-0910）。キー設計は {@link MfaEmailOtpKeys}。
 *
 * <p>用途（{@link MfaEmailOtpPurpose}）ごとに名前空間を分け、ある用途・主体で発行したコードを
 * 別の用途・主体で使えないようにする（AC-23/AC-51）。主体は LOGIN が MFA セッションの sessionHash、
 * SETUP / REAUTH が userId（{@link MfaEmailOtpKeys#subjectOf(Long)}）。</p>
 *
 * <p>呼び出し側の前提（{@link Auth2faService} が担う）:</p>
 * <ul>
 *   <li>LOGIN の送信: IP レート制限・MFA セッション解決・ロック確認は呼び出し側で済ませてから呼ぶ。</li>
 *   <li>LOGIN の照合: 試行回数は MFA セッション側（{@link MfaSessionService#beginAttempt}）で数える。
 *       本部品は LOGIN の照合失敗を独自に数えない。</li>
 * </ul>
 */
public interface MfaEmailOtpService {

    /**
     * 6 桁コードを発行し、ハッシュを Valkey に保存して送信メールを enqueue する。
     *
     * <p>実装の責務: 再送待ち（60 秒・残秒付き 429）、主体ごと・ユーザー単位の送信上限、再送時の旧コード失効、
     * 宛先メールの可否判定（{@link MfaMethodPolicy#isEmailDeliverable(String)}。不可は AUTH_081）、
     * enqueue 失敗時の送信枠・再送待ちの取り消し、LOGIN 送信時の MFA セッション延長
     * （{@link MfaSessionService#extendTtl}）。平文コードはログ・Valkey に置かない。</p>
     *
     * @param purpose   用途
     * @param subject   主体（LOGIN=sessionHash、SETUP/REAUTH=userId 文字列）
     * @param userId    宛先ユーザー
     * @param ipAddress 送信要求元 IP（null 可）
     * @return マスク済みアドレス・有効期限・再送待ち秒数
     */
    MfaEmailSendResponse send(MfaEmailOtpPurpose purpose, String subject, Long userId, String ipAddress);

    /**
     * コードを照合し、一致したらコードを削除する（再利用不可）。不一致・期限切れ・未送信はすべて false。
     *
     * <p>SETUP / REAUTH は {@link MfaEmailOtpKeys#attemptCountKey} で照合失敗を数え、上限で失効させる（AC-45）。
     * LOGIN は数えない（MFA セッション側で数える）。</p>
     *
     * @param purpose 用途
     * @param subject 主体
     * @param code    提示された 6 桁コード
     * @return 一致してこの呼び出しがコードを消費できたら true
     */
    boolean verifyAndConsume(MfaEmailOtpPurpose purpose, String subject, String code);

    /**
     * 用途・主体のコードと照合失敗回数を破棄する（無効化・方式切替・セッション破棄時）。
     * 送信回数・再送待ちは送信爆撃対策のため残す。
     *
     * @param purpose 用途
     * @param subject 主体
     */
    void invalidate(MfaEmailOtpPurpose purpose, String subject);
}
