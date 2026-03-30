package com.mannschaft.app.webhook.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.webhook.WebhookErrorCode;
import com.mannschaft.app.webhook.entity.IncomingWebhookTokenEntity;
import com.mannschaft.app.webhook.repository.IncomingWebhookTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Incoming Webhook管理サービス。
 * 外部サービスからのWebhook受信トークン管理とイベント処理を担う。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class IncomingWebhookService {

    /** スコープ内トークン上限数 */
    private static final int MAX_TOKENS_PER_SCOPE = 5;

    private final IncomingWebhookTokenRepository tokenRepository;

    // ========================================
    // DTOクラス定義
    // ========================================

    /**
     * Incoming Webhookトークン作成リクエストDTO。
     */
    public record CreateIncomingWebhookRequest(
            String scopeType,
            Long scopeId,
            String name,
            String description,
            List<String> allowedIps
    ) {}

    /**
     * Incoming Webhookトークンレスポンスリ DTO。
     */
    public record IncomingWebhookTokenResponse(
            Long id,
            String scopeType,
            Long scopeId,
            String name,
            String token,
            boolean isActive,
            String description,
            LocalDateTime createdAt
    ) {}

    // ========================================
    // 公開メソッド
    // ========================================

    /**
     * Incoming Webhookトークンを作成する。
     * <ul>
     *   <li>token = UUID v4</li>
     *   <li>スコープ内上限（5件）チェック</li>
     * </ul>
     *
     * @param createdBy 作成者ユーザーID
     * @param req       作成リクエスト
     * @return 作成されたトークン情報
     */
    @Transactional
    public ApiResponse<IncomingWebhookTokenResponse> createToken(Long createdBy, CreateIncomingWebhookRequest req) {
        // スコープ内トークン数チェック（論理削除済みを除く）
        int count = tokenRepository.countByScopeTypeAndScopeIdAndDeletedAtIsNull(req.scopeType(), req.scopeId());
        if (count >= MAX_TOKENS_PER_SCOPE) {
            throw new BusinessException(WebhookErrorCode.WEBHOOK_006);
        }

        // トークンをUUID v4で生成
        String token = UUID.randomUUID().toString();

        // エンティティを保存
        IncomingWebhookTokenEntity entity = IncomingWebhookTokenEntity.builder()
                .scopeType(req.scopeType())
                .scopeId(req.scopeId())
                .name(req.name())
                .token(token)
                .createdBy(createdBy)
                .build();

        IncomingWebhookTokenEntity saved = tokenRepository.save(entity);

        log.info("Incoming Webhookトークン作成: id={}, scope={}/{}", saved.getId(), req.scopeType(), req.scopeId());
        return ApiResponse.of(toResponse(saved, req.description()));
    }

    /**
     * スコープに紐づくIncoming Webhookトークン一覧を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return トークン一覧
     */
    public ApiResponse<List<IncomingWebhookTokenResponse>> listTokens(String scopeType, Long scopeId) {
        // JPA の @SQLRestriction により deleted_at IS NULL は自動適用
        List<IncomingWebhookTokenEntity> entities = tokenRepository.findAll().stream()
                .filter(t -> t.getScopeType().equals(scopeType) && t.getScopeId().equals(scopeId))
                .collect(Collectors.toList());
        List<IncomingWebhookTokenResponse> responses = entities.stream()
                .map(e -> toResponse(e, null))
                .collect(Collectors.toList());
        return ApiResponse.of(responses);
    }

    /**
     * Incoming Webhookトークンを失効（論理削除）する。
     *
     * @param id トークンID
     */
    @Transactional
    public void revokeToken(Long id) {
        IncomingWebhookTokenEntity entity = findTokenOrThrow(id);
        entity.softDelete();
        tokenRepository.save(entity);
        log.info("Incoming Webhookトークン失効: id={}", id);
    }

    /**
     * Incoming Webhookリクエストを処理する。
     * <ol>
     *   <li>トークン検証（アクティブかつ未削除）</li>
     *   <li>最終使用日時を更新</li>
     *   <li>受信イベントをログに記録</li>
     * </ol>
     *
     * @param token     受信WebhookのトークンURL
     * @param eventType イベント種別
     * @param payload   受信ペイロード
     */
    @Transactional
    public void processIncoming(String token, String eventType, Map<String, Object> payload) {
        // トークン検証: アクティブかつ未削除
        IncomingWebhookTokenEntity tokenEntity = tokenRepository
                .findByTokenAndIsActiveTrueAndDeletedAtIsNull(token)
                .orElseThrow(() -> new BusinessException(WebhookErrorCode.WEBHOOK_005));

        // 最終使用日時を更新
        tokenEntity.recordUsage();
        tokenRepository.save(tokenEntity);

        log.info("Incoming Webhook受信: tokenId={}, scope={}/{}, eventType={}, payloadKeys={}",
                tokenEntity.getId(),
                tokenEntity.getScopeType(),
                tokenEntity.getScopeId(),
                eventType,
                payload != null ? payload.keySet() : "null");
    }

    // ========================================
    // 内部メソッド
    // ========================================

    /**
     * IDでトークンを取得する。見つからない場合は WEBHOOK_005 例外。
     */
    private IncomingWebhookTokenEntity findTokenOrThrow(Long id) {
        return tokenRepository.findById(id)
                .orElseThrow(() -> new BusinessException(WebhookErrorCode.WEBHOOK_005));
    }

    /**
     * エンティティをレスポンスDTOに変換する。
     */
    private IncomingWebhookTokenResponse toResponse(IncomingWebhookTokenEntity e, String description) {
        return new IncomingWebhookTokenResponse(
                e.getId(),
                e.getScopeType(),
                e.getScopeId(),
                e.getName(),
                e.getToken(),
                e.isActive(),
                description,
                e.getCreatedAt()
        );
    }
}
