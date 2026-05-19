package com.mannschaft.app.advertising.campaign.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.dto.UnsubscribeResultResponse;
import com.mannschaft.app.advertising.campaign.dto.UpdateUserAdPreferencesRequest;
import com.mannschaft.app.advertising.campaign.dto.UserAdPreferenceResponse;
import com.mannschaft.app.advertising.campaign.entity.UserAdPreference;
import com.mannschaft.app.advertising.campaign.enums.AdChannelType;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.advertising.campaign.repository.UserAdPreferenceRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
     * F09.17 Phase 11-b ε-B 配信ワーカー専用 ── Entity 直接取得版。
     *
     * <p>{@link #getOrCreateForUser} は DTO を返すが、配信時にチャネル別オプトアウト判定と
     * {@code blocked_advertiser_account_ids} 判定の両方が必要なため Entity を直接取得する。</p>
     *
     * <p>既存行が無ければデフォルト行を遅延作成する点は {@link #getOrCreateForUser} と同じ。</p>
     *
     * @param userId 受信者ユーザー ID
     * @return preference Entity
     */
    @Transactional
    public UserAdPreference getOrCreateEntityForUser(Long userId) {
        return preferenceRepository.findByUserId(userId)
                .orElseGet(() -> createDefault(userId));
    }

    /**
     * F09.17 Phase 11-b ε-B 配信ワーカー専用 ── {@code blocked_advertiser_account_ids} JSON を
     * List&lt;Long&gt; にデコードして返す。
     *
     * @param entity preference Entity
     * @return ブロック広告主 ID 一覧（空なら empty list）
     */
    public List<Long> decodeBlockedAdvertiserIds(UserAdPreference entity) {
        if (entity == null) {
            return Collections.emptyList();
        }
        return fromJson(entity.getBlockedAdvertiserAccountIds());
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
     * F09.17 Phase 11-b — ワンクリック解除 (unsubscribe JWT 経由) で当該 channel を OFF にする。
     *
     * <p>処理:</p>
     * <ol>
     *   <li>user_ad_preferences 行を取得（無ければデフォルト生成）</li>
     *   <li>{@code unsubscribe_token_version} 一致確認 ── 不一致は
     *       {@link AdCampaignErrorCode#AD_UNSUBSCRIBE_TOKEN_VERSION_MISMATCH} (410)</li>
     *   <li>channel に応じて {@code accept_*_ads = false} を設定</li>
     * </ol>
     *
     * <p>本メソッドは冪等。既に false でも再度 false を書き戻すだけで成功させる。</p>
     *
     * @param userId               JWT の uid claim
     * @param channel              "ANNOUNCEMENT"/"EMAIL"/"PUSH"/"BANNER"
     * @param tokenVersionExpected JWT の ver claim
     * @throws BusinessException token_version 不一致時
     */
    @Transactional
    public void unsubscribe(Long userId, String channel, Integer tokenVersionExpected) {
        if (userId == null || channel == null || tokenVersionExpected == null) {
            throw new BusinessException(AdCampaignErrorCode.AD_UNSUBSCRIBE_TOKEN_INVALID);
        }
        UserAdPreference entity = preferenceRepository.findByUserId(userId)
                .orElseGet(() -> createDefault(userId));

        Integer current = entity.getUnsubscribeTokenVersion();
        int currentVer = (current == null ? 0 : current);
        if (currentVer != tokenVersionExpected) {
            // ローテート済 = 古いトークンは無効
            throw new BusinessException(AdCampaignErrorCode.AD_UNSUBSCRIBE_TOKEN_VERSION_MISMATCH);
        }

        switch (channel) {
            case "ANNOUNCEMENT" -> entity.setAcceptAnnouncementAds(Boolean.FALSE);
            case "EMAIL"        -> entity.setAcceptEmailAds(Boolean.FALSE);
            case "PUSH"         -> entity.setAcceptPushAds(Boolean.FALSE);
            case "BANNER"       -> entity.setAcceptBannerAds(Boolean.FALSE);
            default -> throw new BusinessException(AdCampaignErrorCode.AD_UNSUBSCRIBE_TOKEN_INVALID);
        }
        preferenceRepository.save(entity);
    }

    /**
     * F09.17 残課題 4 — 公開 unsubscribe SPA から複数チャネルを一括 OFF にする。
     *
     * <p>{@link #unsubscribe(Long, String, Integer)} は JWT の {@code ch} クレームに従う
     * 単一チャネル切替だが、SPA はチェックボックスで複数チャネル選択を可能にするため
     * 本メソッドを用意する。</p>
     *
     * <p>処理:</p>
     * <ol>
     *   <li>{@code user_ad_preferences} 行を取得（無ければデフォルト生成）</li>
     *   <li>{@code unsubscribe_token_version} 一致確認 ── 不一致は
     *       {@link AdCampaignErrorCode#AD_UNSUBSCRIBE_TOKEN_VERSION_MISMATCH} (410)</li>
     *   <li>指定チャネルごとに {@code accept_*_ads = false} を冪等に設定</li>
     *   <li>{@link UnsubscribeResultResponse} を組み立てて返却</li>
     * </ol>
     *
     * <p>本メソッドは冪等。既に OFF のチャネルでも例外なく再度 false を書き戻す。
     * 入力 channels が空の場合は {@link AdCampaignErrorCode#AD_UNSUBSCRIBE_TOKEN_INVALID} を投げる
     * （Controller / DTO バリデーションでもブロックされるが二重防御）。</p>
     *
     * <p>監査ログ専用イベントは Phase 11-b 系列で未整備のため、本メソッドでは {@code log.info}
     * での記録に留める（既存 GET 経由の {@link #unsubscribe} と同方針）。</p>
     *
     * @param userId               JWT の uid claim
     * @param channels             OFF にしたいチャネル一覧（最低 1 件）
     * @param tokenVersionExpected JWT の ver claim
     * @return 確定後の disabled / remaining-active チャネル一覧
     * @throws BusinessException token_version 不一致 / 入力不正時
     */
    @Transactional
    public UnsubscribeResultResponse applyChannelUnsubscribe(
            Long userId, List<AdChannelType> channels, Integer tokenVersionExpected) {
        if (userId == null || channels == null || channels.isEmpty() || tokenVersionExpected == null) {
            throw new BusinessException(AdCampaignErrorCode.AD_UNSUBSCRIBE_TOKEN_INVALID);
        }

        UserAdPreference entity = preferenceRepository.findByUserId(userId)
                .orElseGet(() -> createDefault(userId));

        Integer current = entity.getUnsubscribeTokenVersion();
        int currentVer = (current == null ? 0 : current);
        if (currentVer != tokenVersionExpected) {
            // ローテート済 = 古いトークンは無効
            throw new BusinessException(AdCampaignErrorCode.AD_UNSUBSCRIBE_TOKEN_VERSION_MISMATCH);
        }

        // 重複排除 + 入力順保持
        Set<AdChannelType> targets = new LinkedHashSet<>(channels);
        for (AdChannelType ch : targets) {
            switch (ch) {
                case ANNOUNCEMENT -> entity.setAcceptAnnouncementAds(Boolean.FALSE);
                case EMAIL        -> entity.setAcceptEmailAds(Boolean.FALSE);
                case PUSH         -> entity.setAcceptPushAds(Boolean.FALSE);
                case BANNER       -> entity.setAcceptBannerAds(Boolean.FALSE);
            }
        }
        preferenceRepository.save(entity);

        List<AdChannelType> disabled = new ArrayList<>(targets);
        List<AdChannelType> remaining = new ArrayList<>();
        if (Boolean.TRUE.equals(entity.getAcceptAnnouncementAds())) {
            remaining.add(AdChannelType.ANNOUNCEMENT);
        }
        if (Boolean.TRUE.equals(entity.getAcceptEmailAds())) {
            remaining.add(AdChannelType.EMAIL);
        }
        if (Boolean.TRUE.equals(entity.getAcceptPushAds())) {
            remaining.add(AdChannelType.PUSH);
        }
        if (Boolean.TRUE.equals(entity.getAcceptBannerAds())) {
            remaining.add(AdChannelType.BANNER);
        }

        log.info("ad unsubscribe SPA applied userId={} disabledChannels={} remaining={}",
                userId, disabled, remaining);

        return new UnsubscribeResultResponse(
                disabled,
                remaining,
                "advertising.unsubscribe_spa.success_message");
    }

    /**
     * F09.17 Phase 11-b — {@code unsubscribe_token_version} を +1 し、過去発行 JWT を一括失効させる。
     *
     * <p>受信者本人が「全 unsubscribe リンクを無効化したい」と要求した場合に呼ぶ。
     * {@code rotateUnsubscribeTokens=true} で {@link #updateForUser} 経由でも実行されるが、
     * 単体呼び出し用にここで切り出しておく。</p>
     */
    @Transactional
    public void rotateUnsubscribeTokenVersion(Long userId) {
        UserAdPreference entity = preferenceRepository.findByUserId(userId)
                .orElseGet(() -> createDefault(userId));
        Integer current = entity.getUnsubscribeTokenVersion();
        entity.setUnsubscribeTokenVersion((current == null ? 0 : current) + 1);
        preferenceRepository.save(entity);
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
