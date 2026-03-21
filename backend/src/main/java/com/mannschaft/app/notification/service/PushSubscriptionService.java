package com.mannschaft.app.notification.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.NotificationErrorCode;
import com.mannschaft.app.notification.dto.PushSubscriptionRequest;
import com.mannschaft.app.notification.entity.PushSubscriptionEntity;
import com.mannschaft.app.notification.repository.PushSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * プッシュ購読サービス。Web Push APIの購読管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PushSubscriptionService {

    private final PushSubscriptionRepository pushSubscriptionRepository;

    /**
     * プッシュ購読を登録する。
     *
     * @param userId  ユーザーID
     * @param request 購読リクエスト
     * @return 登録された購読エンティティ
     */
    @Transactional
    public PushSubscriptionEntity subscribe(Long userId, PushSubscriptionRequest request) {
        if (pushSubscriptionRepository.existsByEndpoint(request.getEndpoint())) {
            throw new BusinessException(NotificationErrorCode.SUBSCRIPTION_ALREADY_EXISTS);
        }

        PushSubscriptionEntity entity = PushSubscriptionEntity.builder()
                .userId(userId)
                .endpoint(request.getEndpoint())
                .p256dhKey(request.getP256dhKey())
                .authKey(request.getAuthKey())
                .userAgent(request.getUserAgent())
                .build();

        PushSubscriptionEntity saved = pushSubscriptionRepository.save(entity);
        log.info("プッシュ購読登録: userId={}, subscriptionId={}", userId, saved.getId());
        return saved;
    }

    /**
     * プッシュ購読を解除する。
     *
     * @param userId   ユーザーID
     * @param endpoint エンドポイントURL
     */
    @Transactional
    public void unsubscribe(Long userId, String endpoint) {
        PushSubscriptionEntity entity = pushSubscriptionRepository.findByEndpoint(endpoint)
                .orElseThrow(() -> new BusinessException(NotificationErrorCode.SUBSCRIPTION_NOT_FOUND));

        if (!entity.getUserId().equals(userId)) {
            throw new BusinessException(NotificationErrorCode.SUBSCRIPTION_NOT_FOUND);
        }

        pushSubscriptionRepository.delete(entity);
        log.info("プッシュ購読解除: userId={}, endpoint={}", userId, endpoint);
    }

    /**
     * ユーザーのプッシュ購読一覧を取得する。
     *
     * @param userId ユーザーID
     * @return 購読エンティティリスト
     */
    public List<PushSubscriptionEntity> listSubscriptions(Long userId) {
        return pushSubscriptionRepository.findByUserId(userId);
    }
}
