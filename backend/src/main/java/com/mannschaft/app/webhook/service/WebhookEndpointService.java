package com.mannschaft.app.webhook.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.webhook.WebhookErrorCode;
import com.mannschaft.app.webhook.entity.WebhookEndpointEntity;
import com.mannschaft.app.webhook.entity.WebhookEventSubscriptionEntity;
import com.mannschaft.app.webhook.repository.WebhookEndpointRepository;
import com.mannschaft.app.webhook.repository.WebhookEventSubscriptionRepository;
import com.mannschaft.app.webhook.util.SsrfGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Webhookエンドポイント管理サービス。
 * Outgoing Webhookのエンドポイント登録・更新・削除・照会を担う。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class WebhookEndpointService {

    /** エンドポイント上限数 */
    private static final int MAX_ENDPOINTS_PER_SCOPE = 10;

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookEventSubscriptionRepository subscriptionRepository;
    private final SsrfGuard ssrfGuard;

    // ========================================
    // DTOクラス定義
    // ========================================

    /**
     * Webhookエンドポイント作成リクエストDTO。
     */
    public record CreateWebhookEndpointRequest(
            String scopeType,
            Long scopeId,
            String name,
            String url,
            String description,
            Integer timeoutMs,
            List<String> eventTypes
    ) {}

    /**
     * Webhookエンドポイント更新リクエストDTO。
     * nullフィールドは変更なしとして扱う。
     */
    public record UpdateWebhookEndpointRequest(
            String name,
            String url,
            String description,
            Integer timeoutMs,
            Boolean isActive,
            List<String> eventTypes
    ) {}

    /**
     * Webhookエンドポイントレスポンスリ DTO（signingSecretを含まない）。
     */
    public record WebhookEndpointResponse(
            Long id,
            String scopeType,
            Long scopeId,
            String name,
            String url,
            boolean isActive,
            String description,
            int timeoutMs,
            List<String> eventTypes,
            LocalDateTime createdAt
    ) {}

    /**
     * Webhookエンドポイント作成時レスポンスDTO（signingSecretを含む・作成時1回のみ）。
     */
    public record WebhookEndpointCreatedResponse(
            Long id,
            String scopeType,
            Long scopeId,
            String name,
            String url,
            boolean isActive,
            String description,
            int timeoutMs,
            List<String> eventTypes,
            LocalDateTime createdAt,
            String signingSecret
    ) {}

    // ========================================
    // 公開メソッド
    // ========================================

    /**
     * Webhookエンドポイントを作成する。
     * <ul>
     *   <li>SSRF対策: URLをSSRFガードで検証</li>
     *   <li>スコープ内上限（10件）チェック</li>
     *   <li>signingSecretはランダム32バイトをBase64エンコードして生成</li>
     *   <li>購読イベント(eventTypes)をサブスクリプションとして保存</li>
     * </ul>
     *
     * @param createdBy 作成者ユーザーID
     * @param req       作成リクエスト
     * @return 作成されたエンドポイント（signingSecret含む）
     */
    @Transactional
    public ApiResponse<WebhookEndpointCreatedResponse> createEndpoint(Long createdBy, CreateWebhookEndpointRequest req) {
        // SSRF対策: URLを検証
        validateUrl(req.url());

        // スコープ内エンドポイント数チェック（論理削除済みを除く）
        int count = endpointRepository.countByScopeTypeAndScopeIdAndDeletedAtIsNull(req.scopeType(), req.scopeId());
        if (count >= MAX_ENDPOINTS_PER_SCOPE) {
            throw new BusinessException(WebhookErrorCode.WEBHOOK_004);
        }

        // signingSecretをランダム32バイトのBase64文字列として生成
        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);
        String signingSecret = Base64.getEncoder().encodeToString(randomBytes);

        // エンドポイントエンティティを保存
        WebhookEndpointEntity entity = WebhookEndpointEntity.builder()
                .scopeType(req.scopeType())
                .scopeId(req.scopeId())
                .name(req.name())
                .url(req.url())
                .signingSecret(signingSecret)
                .timeoutMs(req.timeoutMs() != null ? req.timeoutMs() : 10000)
                .createdBy(createdBy)
                .build();

        WebhookEndpointEntity saved = endpointRepository.save(entity);

        // 購読イベントを保存
        if (req.eventTypes() != null && !req.eventTypes().isEmpty()) {
            saveSubscriptions(saved.getId(), req.eventTypes());
        }

        log.info("Webhookエンドポイント作成: id={}, scope={}/{}, url={}", saved.getId(), req.scopeType(), req.scopeId(), req.url());

        // 購読イベント一覧を取得してレスポンス返却（signingSecretを作成時のみ含める）
        List<String> eventTypes = getEventTypes(saved.getId());
        WebhookEndpointCreatedResponse response = toCreatedResponse(saved, eventTypes, signingSecret);
        return ApiResponse.of(response);
    }

    /**
     * Webhookエンドポイントを取得する。
     *
     * @param id エンドポイントID
     * @return エンドポイント情報
     */
    public ApiResponse<WebhookEndpointResponse> getEndpoint(Long id) {
        WebhookEndpointEntity entity = findEndpointOrThrow(id);
        List<String> eventTypes = getEventTypes(id);
        return ApiResponse.of(toResponse(entity, eventTypes));
    }

    /**
     * スコープに紐づくWebhookエンドポイント一覧を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return エンドポイント一覧
     */
    public ApiResponse<List<WebhookEndpointResponse>> listEndpoints(String scopeType, Long scopeId) {
        List<WebhookEndpointEntity> entities =
                endpointRepository.findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(scopeType, scopeId);
        List<WebhookEndpointResponse> responses = entities.stream()
                .map(e -> toResponse(e, getEventTypes(e.getId())))
                .collect(Collectors.toList());
        return ApiResponse.of(responses);
    }

    /**
     * Webhookエンドポイントを更新する。
     * <ul>
     *   <li>URL変更時はSSRFガードで再検証</li>
     *   <li>eventTypesが渡された場合は既存購読を削除して再登録</li>
     * </ul>
     *
     * @param id  エンドポイントID
     * @param req 更新リクエスト
     * @return 更新後エンドポイント情報
     */
    @Transactional
    public ApiResponse<WebhookEndpointResponse> updateEndpoint(Long id, UpdateWebhookEndpointRequest req) {
        WebhookEndpointEntity entity = findEndpointOrThrow(id);

        // URL変更時はSSRFガードで再検証
        String newUrl = req.url() != null ? req.url() : entity.getUrl();
        if (req.url() != null && !req.url().equals(entity.getUrl())) {
            validateUrl(req.url());
        }

        // フィールドを更新（nullは変更なし）
        WebhookEndpointEntity.WebhookEndpointEntityBuilder builder = entity.toBuilder()
                .name(req.name() != null ? req.name() : entity.getName())
                .url(newUrl)
                .timeoutMs(req.timeoutMs() != null ? req.timeoutMs() : entity.getTimeoutMs());

        // description はnullを許容するフィールドなので別扱い（省略）
        WebhookEndpointEntity updated = builder.build();

        // isActive フラグ更新（toBuilderでbooleanをそのまま引き継ぐため明示的に操作）
        // NOTE: isActiveはbooleanのため、toBuilder後にactivate/deactivate メソッドを使用
        // updated.toBuilder はコピーを生成するため、ここでは保存後にactivate/deactivateを呼ぶ
        WebhookEndpointEntity saved = endpointRepository.save(updated);

        if (req.isActive() != null) {
            if (req.isActive()) {
                saved.activate();
            } else {
                saved.deactivate();
            }
            saved = endpointRepository.save(saved);
        }

        // eventTypesが渡された場合は購読を再登録
        if (req.eventTypes() != null) {
            // 既存購読を全削除
            List<WebhookEventSubscriptionEntity> existing = subscriptionRepository.findByEndpointId(id);
            subscriptionRepository.deleteAll(existing);
            // 新規購読を登録
            if (!req.eventTypes().isEmpty()) {
                saveSubscriptions(id, req.eventTypes());
            }
        }

        log.info("Webhookエンドポイント更新: id={}", id);
        List<String> eventTypes = getEventTypes(saved.getId());
        return ApiResponse.of(toResponse(saved, eventTypes));
    }

    /**
     * Webhookエンドポイントを論理削除する。
     *
     * @param id エンドポイントID
     */
    @Transactional
    public void deleteEndpoint(Long id) {
        WebhookEndpointEntity entity = findEndpointOrThrow(id);
        entity.softDelete();
        endpointRepository.save(entity);
        log.info("Webhookエンドポイント論理削除: id={}", id);
    }

    // ========================================
    // 内部メソッド
    // ========================================

    /**
     * IDでエンドポイントを取得する。見つからない場合は WEBHOOK_001 例外。
     */
    WebhookEndpointEntity findEndpointOrThrow(Long id) {
        return endpointRepository.findById(id)
                .orElseThrow(() -> new BusinessException(WebhookErrorCode.WEBHOOK_001));
    }

    /**
     * URLをSSRFガードで検証する。UnknownHostExceptionはビジネス例外に変換しない（ホスト不明は許容）。
     */
    private void validateUrl(String url) {
        try {
            ssrfGuard.validate(url);
        } catch (BusinessException e) {
            throw e;
        } catch (UnknownHostException e) {
            // ホスト名解決不能はそのままスロー（接続不可判断はしない）
            throw new RuntimeException("ホスト名解決エラー: " + e.getMessage(), e);
        }
    }

    /**
     * エンドポイントの購読イベント種別一覧を取得する。
     */
    private List<String> getEventTypes(Long endpointId) {
        return subscriptionRepository.findByEndpointId(endpointId)
                .stream()
                .map(WebhookEventSubscriptionEntity::getEventType)
                .collect(Collectors.toList());
    }

    /**
     * 購読イベント種別リストをWebhookEventSubscriptionEntityとして保存する。
     */
    private void saveSubscriptions(Long endpointId, List<String> eventTypes) {
        List<WebhookEventSubscriptionEntity> subscriptions = eventTypes.stream()
                .distinct()
                .map(eventType -> WebhookEventSubscriptionEntity.builder()
                        .endpointId(endpointId)
                        .eventType(eventType)
                        .build())
                .collect(Collectors.toList());
        subscriptionRepository.saveAll(subscriptions);
    }

    /**
     * エンティティをレスポンスDTOに変換する（signingSecretなし）。
     */
    private WebhookEndpointResponse toResponse(WebhookEndpointEntity e, List<String> eventTypes) {
        return new WebhookEndpointResponse(
                e.getId(),
                e.getScopeType(),
                e.getScopeId(),
                e.getName(),
                e.getUrl(),
                e.isActive(),
                null, // description はエンティティにdescriptionフィールドがないため null
                e.getTimeoutMs(),
                eventTypes,
                e.getCreatedAt()
        );
    }

    /**
     * エンティティを作成レスポンスDTOに変換する（signingSecret含む）。
     */
    private WebhookEndpointCreatedResponse toCreatedResponse(WebhookEndpointEntity e, List<String> eventTypes, String signingSecret) {
        return new WebhookEndpointCreatedResponse(
                e.getId(),
                e.getScopeType(),
                e.getScopeId(),
                e.getName(),
                e.getUrl(),
                e.isActive(),
                null,
                e.getTimeoutMs(),
                eventTypes,
                e.getCreatedAt(),
                signingSecret
        );
    }
}
