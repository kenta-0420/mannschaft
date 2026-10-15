package com.mannschaft.app.social.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.social.FollowerType;
import com.mannschaft.app.social.repository.FollowRepository;
import com.mannschaft.app.social.repository.UserSocialProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 退会匿名化イベントに応答して social ドメインの関連データを削除・無効化するリスナー。
 *
 * <p>処理内容:
 * <ul>
 *   <li>フォロー関係の全削除（フォロワー・フォロー対象両側）</li>
 *   <li>ソーシャルプロフィールの無効化（個人特定情報の非表示化）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SocialAnonymizationEventListener {

    private final FollowRepository followRepository;
    private final UserSocialProfileRepository userSocialProfileRepository;

    /**
     * ユーザー退会匿名化イベントを受け取り、social ドメインの関連データを削除・無効化する。
     *
     * @param event 退会匿名化イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると退会済み利用者のソーシャル連携情報に個人情報が残存し、退会済みなのに PII が残るという不整合になる")
    @Async("event-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleUserAnonymized(UserAnonymizedEvent event) {
        Long userId = event.getUserId();
        try {
            followRepository.deleteAllByUserId(userId, FollowerType.USER);
            log.debug("ユーザー退会: フォロー関係削除完了: userId={}", userId);

            userSocialProfileRepository.findByUserId(userId).ifPresent(profile -> {
                profile.deactivate();
                userSocialProfileRepository.save(profile);
                log.debug("ユーザー退会: ソーシャルプロフィール無効化完了: userId={}", userId);
            });

            log.info("ユーザー退会: socialドメイン匿名化完了: userId={}", userId);
        } catch (Exception e) {
            log.warn("ユーザー退会: socialドメイン匿名化失敗: userId={}, error={}",
                    userId, e.getMessage(), e);
        }
    }
}
