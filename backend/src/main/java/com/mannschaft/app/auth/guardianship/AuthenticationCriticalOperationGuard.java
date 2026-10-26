package com.mannschaft.app.auth.guardianship;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import com.mannschaft.app.proxy.ProxyInputContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 後見切替セッション（acting-as）における認証クリティカル操作のガード（F08.9 P3b）。
 *
 * <p>切替は JWT 再発行せず actor=保護者のまま {@code X-Proxy-For-User-Id=child} を
 * {@link com.mannschaft.app.proxy.ProxyInputContextFilter}（F14.1）で検証し、
 * {@link ProxyInputContext#isProxy()} が真になる。設計書 03_security §3.2
 * 「切替セッションの安全境界（なりすまし防止）」により、切替中に保護者が子に対して
 * 行えない認証クリティカル操作（パスワード変更・2FA設定・メール変更・退会・退会取消）を
 * このガードで一律 403 拒否する。</p>
 *
 * <p>散逸を避けるため、各 Controller / Service 入口から 1 行
 * {@link #assertNotActingAs()} を呼ぶ形に統一する。</p>
 *
 * <p><b>クロスドメイン参照について（TODO）</b>：エラーコードは F08.9 の体系
 * （payment ドメインの {@link com.mannschaft.app.payment.MembershipBillingErrorCode}）を
 * 暫定参照する。auth→payment のクロスドメイン参照であり、将来のドメイン分割時には
 * auth 側の独立エラーコードへの移行を検討する。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthenticationCriticalOperationGuard {

    private final ProxyInputContext proxyInputContext;

    /**
     * 後見切替セッション中（{@code isProxy()==true}）であれば認証クリティカル操作を拒否する。
     * 通常入力（本人操作）の場合は何もしない。
     *
     * @throws BusinessException 切替セッション中の場合
     *         （{@link MembershipBillingErrorCode#MEMBERSHIP_AUTHENTICATION_CRITICAL_OPERATION} / 403）
     */
    public void assertNotActingAs() {
        if (proxyInputContext.isProxy()) {
            log.warn("後見切替セッション中の認証クリティカル操作を拒否: subjectUserId={}, consentId={}",
                    proxyInputContext.getSubjectUserId(), proxyInputContext.getConsentId());
            throw new BusinessException(
                    MembershipBillingErrorCode.MEMBERSHIP_AUTHENTICATION_CRITICAL_OPERATION);
        }
    }
}
