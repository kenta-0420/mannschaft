package com.mannschaft.app.webhook.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.webhook.ApiKeyScopePermission;
import com.mannschaft.app.webhook.WebhookErrorCode;
import com.mannschaft.app.webhook.entity.ApiKeyEntity;
import com.mannschaft.app.webhook.repository.ApiKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * APIキー管理サービス。
 * 外部連携用APIキーの発行・一覧・失効・検証を担う。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ApiKeyService {

    /** APIキープレフィックス文字列 */
    private static final String KEY_PREFIX_STR = "mft_";

    /** スコープ内APIキー上限数 */
    private static final int MAX_API_KEYS_PER_SCOPE = 5;

    // 既存の @Bean passwordEncoder (AuthConfig) を注入
    private final PasswordEncoder passwordEncoder;
    private final ApiKeyRepository apiKeyRepository;

    // ========================================
    // DTOクラス定義
    // ========================================

    /**
     * APIキー発行リクエストDTO。
     */
    public record IssueApiKeyRequest(
            String scopeType,
            Long scopeId,
            String name,
            String description,
            List<String> permissions,
            LocalDate expiresAt
    ) {}

    /**
     * APIキーレスポンスDTO（keyHashは含まない）。
     */
    public record ApiKeyResponse(
            Long id,
            String scopeType,
            Long scopeId,
            String name,
            String keyPrefix,
            String description,
            List<String> permissions,
            LocalDate expiresAt,
            LocalDateTime createdAt
    ) {}

    /**
     * APIキー発行時レスポンスDTO（rawKeyを含む・発行時1回のみ）。
     */
    public record ApiKeyIssuedResponse(
            Long id,
            String scopeType,
            Long scopeId,
            String name,
            String keyPrefix,
            String description,
            List<String> permissions,
            LocalDate expiresAt,
            LocalDateTime createdAt,
            String rawKey
    ) {}

    /**
     * APIキー検証結果DTO（認証フィルタ用）。
     */
    public record ApiKeyVerifyResult(
            Long id,
            String scopeType,
            Long scopeId,
            ApiKeyScopePermission scopePermission
    ) {}

    // ========================================
    // 公開メソッド
    // ========================================

    /**
     * APIキーを発行する。
     * <ul>
     *   <li>rawKey = "mft_" + UUID（ハイフンなし）</li>
     *   <li>keyPrefix = rawKeyの先頭8文字（表示・絞り込み用）</li>
     *   <li>keyHash = BCryptでハッシュ化（DBに保存）</li>
     *   <li>rawKeyはレスポンスに1回のみ含める</li>
     * </ul>
     *
     * @param createdBy 作成者ユーザーID
     * @param req       発行リクエスト
     * @return 発行されたAPIキー情報（rawKey含む）
     */
    @Transactional
    public ApiResponse<ApiKeyIssuedResponse> issueApiKey(Long createdBy, IssueApiKeyRequest req) {
        // スコープ内APIキー数チェック
        int count = apiKeyRepository.countByScopeTypeAndScopeIdAndDeletedAtIsNull(req.scopeType(), req.scopeId());
        if (count >= MAX_API_KEYS_PER_SCOPE) {
            throw new BusinessException(WebhookErrorCode.WEBHOOK_008);
        }

        // rawKey生成: "mft_" + UUIDハイフン除去（32文字部分） = 計36文字
        String rawKey = KEY_PREFIX_STR + UUID.randomUUID().toString().replace("-", "");

        // keyPrefix: 先頭8文字（"mft_xxxx"）
        String keyPrefix = rawKey.substring(0, 8);

        // keyHash: BCryptでハッシュ化
        String keyHash = passwordEncoder.encode(rawKey);

        // エンティティ保存
        LocalDateTime expiresAtDt = req.expiresAt() != null
                ? req.expiresAt().atStartOfDay()
                : null;

        ApiKeyEntity entity = ApiKeyEntity.builder()
                .scopeType(req.scopeType())
                .scopeId(req.scopeId())
                .name(req.name())
                .keyPrefix(keyPrefix)
                .keyHash(keyHash)
                .expiresAt(expiresAtDt)
                .createdBy(createdBy)
                .build();

        ApiKeyEntity saved = apiKeyRepository.save(entity);

        log.info("APIキー発行: id={}, scope={}/{}, prefix={}", saved.getId(), req.scopeType(), req.scopeId(), keyPrefix);

        // rawKeyはレスポンスに1回のみ含める
        ApiKeyIssuedResponse response = toIssuedResponse(saved, req.permissions(), req.description(), rawKey);
        return ApiResponse.of(response);
    }

    /**
     * スコープに紐づくAPIキー一覧を取得する（keyHashは除外）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return APIキー一覧
     */
    public ApiResponse<List<ApiKeyResponse>> listApiKeys(String scopeType, Long scopeId) {
        // JPA の @SQLRestriction により deleted_at IS NULL は自動適用
        List<ApiKeyEntity> entities = apiKeyRepository.findAll().stream()
                .filter(k -> k.getScopeType().equals(scopeType) && k.getScopeId().equals(scopeId))
                .collect(Collectors.toList());
        List<ApiKeyResponse> responses = entities.stream()
                .map(e -> toResponse(e, null, null))
                .collect(Collectors.toList());
        return ApiResponse.of(responses);
    }

    /**
     * APIキーを失効（論理削除）する。
     *
     * @param id APIキーID
     */
    @Transactional
    public void revokeApiKey(Long id) {
        ApiKeyEntity entity = findApiKeyOrThrow(id);
        entity.softDelete();
        apiKeyRepository.save(entity);
        log.info("APIキー失効: id={}", id);
    }

    /**
     * rawKeyを使ってAPIキーを検証し、スコープ・パーミッション情報を返す（認証フィルタ用）。
     * <ol>
     *   <li>keyPrefixで候補を絞り込む</li>
     *   <li>BCryptでrawKeyと照合</li>
     *   <li>有効期限チェック</li>
     * </ol>
     *
     * @param rawKey 検証対象のAPIキー文字列
     * @return 検証結果（スコープ・パーミッション）
     * @throws BusinessException 無効なAPIキーまたは期限切れの場合
     */
    @Transactional
    public ApiKeyVerifyResult verifyApiKey(String rawKey) {
        if (rawKey == null || rawKey.length() < 8) {
            throw new BusinessException(WebhookErrorCode.WEBHOOK_007);
        }

        // keyPrefixで候補を絞り込む
        String prefix = rawKey.substring(0, 8);
        List<ApiKeyEntity> candidates =
                apiKeyRepository.findByKeyPrefixAndIsActiveTrueAndDeletedAtIsNull(prefix);

        // BCryptで照合
        Optional<ApiKeyEntity> matched = candidates.stream()
                .filter(k -> passwordEncoder.matches(rawKey, k.getKeyHash()))
                .findFirst();

        ApiKeyEntity apiKey = matched
                .orElseThrow(() -> new BusinessException(WebhookErrorCode.WEBHOOK_007));

        // 有効期限チェック
        if (apiKey.isExpired()) {
            throw new BusinessException(WebhookErrorCode.WEBHOOK_011);
        }

        // 最終使用日時を更新
        apiKey.recordUsage();
        apiKeyRepository.save(apiKey);

        log.debug("APIキー検証成功: id={}, scope={}/{}", apiKey.getId(), apiKey.getScopeType(), apiKey.getScopeId());
        return new ApiKeyVerifyResult(
                apiKey.getId(),
                apiKey.getScopeType(),
                apiKey.getScopeId(),
                apiKey.getScopePermission()
        );
    }

    // ========================================
    // 内部メソッド
    // ========================================

    /**
     * IDでAPIキーを取得する。見つからない場合は WEBHOOK_007 例外。
     */
    private ApiKeyEntity findApiKeyOrThrow(Long id) {
        return apiKeyRepository.findById(id)
                .orElseThrow(() -> new BusinessException(WebhookErrorCode.WEBHOOK_007));
    }

    /**
     * エンティティをレスポンスDTOに変換する（keyHashは除外）。
     */
    private ApiKeyResponse toResponse(ApiKeyEntity e, List<String> permissions, String description) {
        LocalDate expiresAt = e.getExpiresAt() != null ? e.getExpiresAt().toLocalDate() : null;
        return new ApiKeyResponse(
                e.getId(),
                e.getScopeType(),
                e.getScopeId(),
                e.getName(),
                e.getKeyPrefix(),
                description,
                permissions,
                expiresAt,
                e.getCreatedAt()
        );
    }

    /**
     * エンティティを発行レスポンスDTOに変換する（rawKey含む）。
     */
    private ApiKeyIssuedResponse toIssuedResponse(ApiKeyEntity e, List<String> permissions, String description, String rawKey) {
        LocalDate expiresAt = e.getExpiresAt() != null ? e.getExpiresAt().toLocalDate() : null;
        return new ApiKeyIssuedResponse(
                e.getId(),
                e.getScopeType(),
                e.getScopeId(),
                e.getName(),
                e.getKeyPrefix(),
                description,
                permissions,
                expiresAt,
                e.getCreatedAt(),
                rawKey
        );
    }
}
