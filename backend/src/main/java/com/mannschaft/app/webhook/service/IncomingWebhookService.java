package com.mannschaft.app.webhook.service;

import com.mannschaft.app.common.AccessControlService;
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
    private final AccessControlService accessControlService;

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
        // 認可: 作成先スコープの ADMIN/DEPUTY_ADMIN のみ発行可能
        accessControlService.checkAdminOrAbove(createdBy, req.scopeId(), req.scopeType());

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
     * 生トークンは平文露出防止のためprefix/suffixのみのマスク済み文字列で返す
     * （発行直後（{@link #createToken}）のみ生トークンを1回返す方針）。
     *
     * @param actorUserId 操作者ユーザーID（認可検証用）
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @return トークン一覧（tokenはマスク済み）
     */
    public ApiResponse<List<IncomingWebhookTokenResponse>> listTokens(Long actorUserId, String scopeType, Long scopeId) {
        // 認可: 一覧取得先スコープの ADMIN/DEPUTY_ADMIN のみ閲覧可能（クエリ引数のscopeがそのまま照会対象）
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType);

        // スコープに紐づくトークン一覧を直接取得（論理削除済みを除く）
        List<IncomingWebhookTokenEntity> entities = tokenRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(scopeType, scopeId);
        List<IncomingWebhookTokenResponse> responses = entities.stream()
                .map(e -> toMaskedResponse(e, null))
                .collect(Collectors.toList());
        return ApiResponse.of(responses);
    }

    /**
     * Incoming Webhookトークンを失効（論理削除）する。
     *
     * @param actorUserId 操作者ユーザーID（認可検証用）
     * @param id          トークンID
     */
    @Transactional
    public void revokeToken(Long actorUserId, Long id) {
        // ★BOLA対策: pathのidから対象entityを先にfetchし、entity由来のscopeで認可する
        IncomingWebhookTokenEntity entity = findTokenOrThrow(id);
        accessControlService.checkAdminOrAbove(actorUserId, entity.getScopeId(), entity.getScopeType());

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
     * エンティティをレスポンスDTOに変換する（token含む・発行直後の1回のみ使用）。
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

    /**
     * エンティティをレスポンスDTOに変換する（tokenはマスク済み・一覧取得専用）。
     * 平文の生トークン露出を是正するため、先頭8文字+末尾4文字のみ表示し中間を "****" に置換する。
     */
    private IncomingWebhookTokenResponse toMaskedResponse(IncomingWebhookTokenEntity e, String description) {
        return new IncomingWebhookTokenResponse(
                e.getId(),
                e.getScopeType(),
                e.getScopeId(),
                e.getName(),
                maskToken(e.getToken()),
                e.isActive(),
                description,
                e.getCreatedAt()
        );
    }

    /**
     * トークン文字列をマスクする。先頭8文字+末尾4文字のみ表示し、中間は "****" に置換する。
     * 短いトークン（12文字未満）は全体をマスクする。
     */
    private String maskToken(String token) {
        if (token == null) {
            return null;
        }
        if (token.length() < 12) {
            return "*".repeat(token.length());
        }
        return token.substring(0, 8) + "****" + token.substring(token.length() - 4);
    }
}
