package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.auth.repository.OAuthAccountRepository;
import com.mannschaft.app.auth.repository.TwoFactorAuthRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 退会匿名化イベントに応答して auth ドメインの関連データを削除するリスナー。
 *
 * <p>処理内容:
 * <ul>
 *   <li>OAuth 連携アカウント削除</li>
 *   <li>二要素認証（2FA）設定削除</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthAnonymizationEventListener {

    private final OAuthAccountRepository oAuthAccountRepository;
    private final TwoFactorAuthRepository twoFactorAuthRepository;

    /**
     * ユーザー退会匿名化イベントを受け取り、auth ドメインの関連データを削除する。
     *
     * @param event 退会匿名化イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると退会済み利用者の認証情報・ログイン履歴の個人情報が残存し、退会済みなのに PII が残るという不整合になる")
    @Async("event-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleUserAnonymized(UserAnonymizedEvent event) {
        Long userId = event.getUserId();
        try {
            oAuthAccountRepository.deleteByUserId(userId);
            log.debug("ユーザー退会: OAuth連携削除完了: userId={}", userId);

            twoFactorAuthRepository.deleteByUserId(userId);
            log.debug("ユーザー退会: 2FA設定削除完了: userId={}", userId);

            log.info("ユーザー退会: authドメイン匿名化完了: userId={}", userId);
        } catch (Exception e) {
            log.warn("ユーザー退会: authドメイン匿名化失敗: userId={}, error={}",
                    userId, e.getMessage(), e);
        }
    }
}
