package com.mannschaft.app.advertising.campaign.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.dto.UpdateUserAdPreferencesRequest;
import com.mannschaft.app.advertising.campaign.dto.UserAdPreferenceResponse;
import com.mannschaft.app.advertising.campaign.entity.UserAdPreference;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.advertising.campaign.repository.UserAdPreferenceRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * F09.17 Phase 11-a 受信者の広告受信設定サービス。
 *
 * <p>設計書「Preferences 域」§3〜§4 に対応する。役割:</p>
 * <ul>
 *   <li>初回 GET 時にデフォルト行を遅延作成（accept_*_ads は全 ON、consented_at は NULL、token_version は 0）</li>
 *   <li>PUT で受信フラグを部分更新し、初回 PUT 時のみ {@code consented_at} に現在時刻を記録する</li>
 *   <li>{@code blocked_advertiser_account_ids} 上限 100 件をアプリ層で検証する</li>
 *   <li>{@code rotateUnsubscribeTokens=true} の場合 {@code unsubscribe_token_version} を +1 する</li>
 * </ul>
 *
 * <p>本サービスは Advertising ドメイン内に閉じている（クロスドメイン Repository を呼ばない）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdPreferenceService {

    /** {@code blocked_advertiser_account_ids} の上限（設計書 §5.2）。 */
    public static final int BLOCKED_ADVERTISERS_MAX = 100;

    private static final TypeReference<List<Long>> LONG_LIST_TYPE = new TypeReference<>() {
    };

    private final UserAdPreferenceRepository preferenceRepository;
    private final ObjectMapper objectMapper;

    /**
     * 認証ユーザーの広告受信設定を取得する。
     *
     * <p>既存行が無ければデフォルト行を遅延作成（全許可・未同意・token_version=0）してから返す。</p>
     *
     * @param userId 認証ユーザー ID
     * @return 受信設定レスポンス
     */
    @Transactional
    public UserAdPreferenceResponse getOrCreateForUser(Long userId) {
        UserAdPreference entity = preferenceRepository.findByUserId(userId)
                .orElseGet(() -> createDefault(userId));
        return toResponse(entity);
    }

    /**
     * 認証ユーザーの広告受信設定を更新する。
     *
     * <p>初回 PUT で {@code consented_at} を {@code now()} に設定する。2 回目以降は維持。</p>
     *
     * @param userId  認証ユーザー ID
     * @param request 更新リクエスト（null フィールドは現在値を維持）
     * @return 更新後の受信設定レスポンス
     * @throws BusinessException {@link AdCampaignErrorCode#AD_PREFERENCES_BLOCKED_LIMIT} 上限 100 件超過
     */
    @Transactional
    public UserAdPreferenceResponse updateForUser(Long userId, UpdateUserAdPreferencesRequest request) {
        if (request == null) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        UserAdPreference entity = preferenceRepository.findByUserId(userId)
                .orElseGet(() -> createDefault(userId));

        if (request.acceptAnnouncementAds() != null) {
            entity.setAcceptAnnouncementAds(request.acceptAnnouncementAds());
        }
        if (request.acceptEmailAds() != null) {
            entity.setAcceptEmailAds(request.acceptEmailAds());
        }
        if (request.acceptPushAds() != null) {
            entity.setAcceptPushAds(request.acceptPushAds());
        }
        if (request.acceptBannerAds() != null) {
            entity.setAcceptBannerAds(request.acceptBannerAds());
        }

        if (request.blockedAdvertiserAccountIds() != null) {
            List<Long> blocked = request.blockedAdvertiserAccountIds();
            if (blocked.size() > BLOCKED_ADVERTISERS_MAX) {
                throw new BusinessException(AdCampaignErrorCode.AD_PREFERENCES_BLOCKED_LIMIT);
            }
            entity.setBlockedAdvertiserAccountIds(toJson(blocked));
        }

        // consented_at は初回 PUT 時のみ now() を記録する（再 PUT では維持）
        if (entity.getConsentedAt() == null) {
            entity.setConsentedAt(LocalDateTime.now());
        }

        // unsubscribe_token_version のローテーション
        if (Boolean.TRUE.equals(request.rotateUnsubscribeTokens())) {
            Integer current = entity.getUnsubscribeTokenVersion();
            entity.setUnsubscribeTokenVersion((current == null ? 0 : current) + 1);
        }

        UserAdPreference saved = preferenceRepository.save(entity);
        return toResponse(saved);
    }

    /**
     * デフォルト行を作成して保存する。
     *
     * <p>accept_*_ads = true / blocked = [] / consented_at = null / token_version = 0。</p>
     */
    private UserAdPreference createDefault(Long userId) {
        UserAdPreference entity = UserAdPreference.builder()
                .userId(userId)
                .acceptAnnouncementAds(Boolean.TRUE)
                .acceptEmailAds(Boolean.TRUE)
                .acceptPushAds(Boolean.TRUE)
                .acceptBannerAds(Boolean.TRUE)
                .blockedAdvertiserAccountIds("[]")
                .unsubscribeTokenVersion(0)
                .consentedAt(null)
                .build();
        return preferenceRepository.save(entity);
    }

    /**
     * Entity → Response 変換。
     */
    private UserAdPreferenceResponse toResponse(UserAdPreference entity) {
        return new UserAdPreferenceResponse(
                entity.getId(),
                entity.getAcceptAnnouncementAds(),
                entity.getAcceptEmailAds(),
                entity.getAcceptPushAds(),
                entity.getAcceptBannerAds(),
                fromJson(entity.getBlockedAdvertiserAccountIds()),
                entity.getConsentedAt(),
                entity.getUnsubscribeTokenVersion(),
                entity.getUpdatedAt());
    }

    private String toJson(List<Long> ids) {
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (JsonProcessingException e) {
            log.error("blocked_advertiser_account_ids の JSON 直列化に失敗しました userId 周辺の操作中", e);
            throw new IllegalStateException("blocked_advertiser_account_ids JSON serialization failed", e);
        }
    }

    private List<Long> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, LONG_LIST_TYPE);
        } catch (JsonProcessingException e) {
            log.error("blocked_advertiser_account_ids の JSON 復元に失敗しました value={}", json, e);
            throw new IllegalStateException("blocked_advertiser_account_ids JSON deserialization failed", e);
        }
    }
}
