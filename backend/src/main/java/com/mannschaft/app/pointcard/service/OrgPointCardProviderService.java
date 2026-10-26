package com.mannschaft.app.pointcard.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.pointcard.dto.CreateOrgProviderRequest;
import com.mannschaft.app.pointcard.dto.CustomerQrResponse;
import com.mannschaft.app.pointcard.dto.PointCardProviderResponse;
import com.mannschaft.app.pointcard.dto.UpdateOrgProviderRequest;
import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.enums.PointCardCategory;
import com.mannschaft.app.pointcard.enums.PointCardProviderType;
import com.mannschaft.app.pointcard.error.PointCardErrorCode;
import com.mannschaft.app.pointcard.event.ProviderCacheRefreshEvent;
import com.mannschaft.app.pointcard.repository.PointCardProviderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * F18 Phase 2 S2B — 組織管理者向け自店プロバイダー CRUD サービス。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6 / §12 / §3.3 UC-8
 *
 * <h2>責務</h2>
 * <ul>
 *   <li>{@code SELF_ISSUED_STAMP} 種別の自店プロバイダー一覧 / 詳細取得</li>
 *   <li>新規発行（{@code type=SELF_ISSUED_STAMP} 固定、{@code code} 自動生成）</li>
 *   <li>編集（{@code type} / {@code organization_id} / {@code code} は不変）</li>
 *   <li>停止（{@code is_active=false} へ更新、物理削除ではない）</li>
 *   <li>顧客追加用 QR コードのディープリンク URL 生成</li>
 * </ul>
 *
 * <h2>認可</h2>
 * <ul>
 *   <li>一覧 / 詳細 / 編集 / 新規発行 / QR: ADMIN または DEPUTY_ADMIN</li>
 *   <li>停止: ADMIN のみ（「停止は意思決定として重い」設計判断）</li>
 * </ul>
 *
 * <h2>監査ログ</h2>
 * <p>create / update / deactivate ごとに {@code POINT_CARD_PROVIDER_CREATED / _UPDATED / _DEACTIVATED}
 * を 1 件記録する。metadata には個人情報を含めず、{@code provider_id / organization_id / display_name}
 * のみ含める。</p>
 *
 * <h2>キャッシュ無効化</h2>
 * <p>create / update / deactivate 後に {@link ProviderCacheRefreshEvent} を発火し、
 * {@link ProviderMatchService} の fuzzy match キャッシュを即時更新する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrgPointCardProviderService {

    /** 1 組織あたりの自店プロバイダー作成上限。停止済はカウントしない。 */
    public static final int PROVIDER_LIMIT_PER_ORG = 20;

    /** {@code code} 自動生成のランダムサフィックス長。UUID 先頭 8 文字を採用。 */
    private static final int CODE_RANDOM_LENGTH = 8;

    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";

    private final PointCardProviderRepository providerRepository;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;

    /** 顧客追加用 QR のディープリンクスキーム。{@code mannschaft://wallet/add-from-qr?providerId=...} */
    @Value("${pointcard.customer-qr.deep-link-base:mannschaft://wallet/add-from-qr}")
    private String deepLinkBase;

    /** Web フォールバック URL の絶対 URL ベース。本番では HTTPS 必須。 */
    @Value("${pointcard.customer-qr.web-base:https://mannschaft.example.com/wallet/add-from-qr}")
    private String webBase;

    // ─────────────────────────────────────────────
    // 一覧 / 詳細
    // ─────────────────────────────────────────────

    /**
     * 当該組織が発行している自店プロバイダー一覧を返す。
     *
     * @param orgId      対象組織 ID（パスから）
     * @param userId     リクエストユーザー（認可検証用）
     * @param activeOnly true なら有効化中のもののみ、false なら停止済を含める
     */
    public List<PointCardProviderResponse> listOrgProviders(
            Long orgId, Long userId, boolean activeOnly) {
        accessControlService.checkAdminOrAbove(userId, orgId, SCOPE_ORGANIZATION);

        List<PointCardProviderEntity> entities = activeOnly
                ? providerRepository.findAllByOrganizationIdAndActiveTrue(orgId)
                : providerRepository.findAllByOrganizationIdOrderByCreatedAtDesc(orgId);
        return entities.stream()
                .map(PointCardProviderResponse::from)
                .toList();
    }

    /**
     * プロバイダー詳細を取得する。所属組織不一致は {@code PROVIDER_NOT_OWNED} (404)。
     */
    public PointCardProviderResponse getOrgProvider(Long orgId, UUID providerId, Long userId) {
        accessControlService.checkAdminOrAbove(userId, orgId, SCOPE_ORGANIZATION);
        PointCardProviderEntity entity = loadProviderOwnedBy(orgId, providerId);
        return PointCardProviderResponse.from(entity);
    }

    // ─────────────────────────────────────────────
    // 新規発行
    // ─────────────────────────────────────────────

    /**
     * 自店プロバイダーを新規発行する。
     *
     * <ol>
     *   <li>認可検証（ADMIN / DEPUTY_ADMIN）</li>
     *   <li>{@code cardNumberRegex} の構文検証（不正は 400）</li>
     *   <li>1 組織あたり 20 個上限チェック → {@code PROVIDER_LIMIT_EXCEEDED} (409)</li>
     *   <li>{@code code = org_{orgId}_{rand8}} 自動生成（UUID 先頭 8 文字、衝突確率ほぼ 0）</li>
     *   <li>{@code type = SELF_ISSUED_STAMP} で保存（API 経由で他種別は発行不可）</li>
     *   <li>監査ログ {@code POINT_CARD_PROVIDER_CREATED}</li>
     *   <li>{@link ProviderCacheRefreshEvent} 発火で fuzzy match キャッシュ更新</li>
     * </ol>
     */
    @Transactional
    public PointCardProviderResponse createOrgProvider(
            Long orgId, Long userId, CreateOrgProviderRequest req) {
        accessControlService.checkAdminOrAbove(userId, orgId, SCOPE_ORGANIZATION);

        // 正規表現の構文を事前検証（不正なまま保存して fuzzy match が落ちないように）
        validateRegex(req.cardNumberRegex());

        long current = providerRepository.countByOrganizationIdAndActiveTrue(orgId);
        if (current >= PROVIDER_LIMIT_PER_ORG) {
            throw new BusinessException(PointCardErrorCode.PROVIDER_LIMIT_EXCEEDED);
        }

        PointCardProviderEntity entity = PointCardProviderEntity.builder()
                .code(generateCode(orgId))
                .displayName(req.displayName())
                // 自店スタンプは「店舗発行」なので OTHER カテゴリで保存する
                .category(PointCardCategory.OTHER)
                .type(PointCardProviderType.SELF_ISSUED_STAMP)
                .organizationId(orgId)
                .brandColor(req.brandColor())
                .logoUrl(req.logoUrl())
                .cardNumberRegex(req.cardNumberRegex())
                .cardNumberLengthHint(req.cardNumberLengthHint())
                .active(Boolean.TRUE)
                .build();

        PointCardProviderEntity saved = providerRepository.save(entity);

        recordProviderAudit(AuditEventType.POINT_CARD_PROVIDER_CREATED.name(),
                userId, orgId, saved);
        eventPublisher.publishEvent(new ProviderCacheRefreshEvent());

        log.info("自店ポイントカードプロバイダーを発行: orgId={}, providerId={}, code={}, displayName={}",
                orgId, saved.getId(), saved.getCode(), saved.getDisplayName());
        return PointCardProviderResponse.from(saved);
    }

    // ─────────────────────────────────────────────
    // 編集
    // ─────────────────────────────────────────────

    /**
     * 自店プロバイダーを部分更新する。
     *
     * <p>{@code type} / {@code organization_id} / {@code code} は不変（リクエスト DTO に含めない設計）。
     * null フィールドは「変更なし」として扱う。
     *
     * <ol>
     *   <li>認可検証（ADMIN / DEPUTY_ADMIN）</li>
     *   <li>所属組織検証（不一致は {@code PROVIDER_NOT_OWNED} 404）</li>
     *   <li>{@code cardNumberRegex} 送信時は構文検証</li>
     *   <li>差分適用 → save → 監査ログ → キャッシュリフレッシュ</li>
     * </ol>
     */
    @Transactional
    public PointCardProviderResponse updateOrgProvider(
            Long orgId, UUID providerId, Long userId, UpdateOrgProviderRequest req) {
        accessControlService.checkAdminOrAbove(userId, orgId, SCOPE_ORGANIZATION);
        PointCardProviderEntity entity = loadProviderOwnedBy(orgId, providerId);

        if (req.cardNumberRegex() != null) {
            validateRegex(req.cardNumberRegex());
        }

        if (req.displayName() != null) {
            entity.setDisplayName(req.displayName());
        }
        if (req.brandColor() != null) {
            entity.setBrandColor(req.brandColor());
        }
        if (req.logoUrl() != null) {
            entity.setLogoUrl(req.logoUrl());
        }
        if (req.cardNumberRegex() != null) {
            entity.setCardNumberRegex(req.cardNumberRegex());
        }
        if (req.cardNumberLengthHint() != null) {
            entity.setCardNumberLengthHint(req.cardNumberLengthHint());
        }

        PointCardProviderEntity saved = providerRepository.save(entity);

        recordProviderAudit(AuditEventType.POINT_CARD_PROVIDER_UPDATED.name(),
                userId, orgId, saved);
        eventPublisher.publishEvent(new ProviderCacheRefreshEvent());

        log.info("自店ポイントカードプロバイダーを編集: orgId={}, providerId={}", orgId, providerId);
        return PointCardProviderResponse.from(saved);
    }

    // ─────────────────────────────────────────────
    // 停止（物理削除ではない）
    // ─────────────────────────────────────────────

    /**
     * プロバイダーを停止する（{@code is_active=false} に更新）。
     *
     * <p>認可は ADMIN のみ（DEPUTY_ADMIN は不可）。停止は意思決定として重い操作のため、
     * 委任副管理者では実行できない設計とする。
     *
     * <p>顧客がすでに保有している {@code user_point_cards} は {@code provider_id} で参照しているが、
     * Phase 1 設計書 §12.3 通りカード本体は削除しない（残スタンプ・履歴を保護）。
     * フロントエンドは {@code provider.isActive=false} を見て「閉店」ラベルを表示する想定。
     *
     * <p>すでに停止済（{@code is_active=false}）の場合は冪等に成功する（再度 false 設定 + 監査ログ）。
     */
    @Transactional
    public void deactivateOrgProvider(Long orgId, UUID providerId, Long userId) {
        // ADMIN のみ。DEPUTY_ADMIN は拒否
        if (!accessControlService.isAdmin(userId, orgId, SCOPE_ORGANIZATION)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        PointCardProviderEntity entity = loadProviderOwnedBy(orgId, providerId);

        entity.setActive(Boolean.FALSE);
        PointCardProviderEntity saved = providerRepository.save(entity);

        recordProviderAudit(AuditEventType.POINT_CARD_PROVIDER_DEACTIVATED.name(),
                userId, orgId, saved);
        eventPublisher.publishEvent(new ProviderCacheRefreshEvent());

        log.info("自店ポイントカードプロバイダーを停止: orgId={}, providerId={}", orgId, providerId);
    }

    // ─────────────────────────────────────────────
    // 顧客 QR ディープリンク
    // ─────────────────────────────────────────────

    /**
     * 顧客追加用 QR コード（ディープリンク URL）の情報を返す。
     *
     * <p>QR 画像本体はフロントエンド側の {@code qrcode} ライブラリで生成する。
     * サーバーは {@code deepLinkUrl} と {@code webUrl} の 2 つを返却する。
     *
     * <p>停止済プロバイダーでも URL は生成して返す（QR を再配布する運用に対応）。
     * 「顧客が読み取ったときに追加できるか」はクライアント側 + 既存
     * {@code POST /api/v1/point-cards} の検証に委ねる。
     */
    public CustomerQrResponse getCustomerQr(Long orgId, UUID providerId, Long userId) {
        accessControlService.checkAdminOrAbove(userId, orgId, SCOPE_ORGANIZATION);
        PointCardProviderEntity entity = loadProviderOwnedBy(orgId, providerId);

        String deepLinkUrl = deepLinkBase + "?providerId=" + entity.getId();
        String webUrl = webBase + "?providerId=" + entity.getId();
        return new CustomerQrResponse(
                entity.getId(),
                entity.getDisplayName(),
                deepLinkUrl,
                webUrl
        );
    }

    // ─────────────────────────────────────────────
    // 補助
    // ─────────────────────────────────────────────

    /**
     * プロバイダーを取得し、所属組織 {@code orgId} と一致することを検証する。
     * 不一致 / 不存在は IDOR 防止のため {@code PROVIDER_NOT_OWNED} (404) を返す
     * （存在自体を秘匿）。
     */
    private PointCardProviderEntity loadProviderOwnedBy(Long orgId, UUID providerId) {
        PointCardProviderEntity entity = providerRepository.findById(providerId)
                .orElseThrow(() -> new BusinessException(PointCardErrorCode.PROVIDER_NOT_OWNED));
        if (entity.getOrganizationId() == null
                || !entity.getOrganizationId().equals(orgId)) {
            throw new BusinessException(PointCardErrorCode.PROVIDER_NOT_OWNED);
        }
        return entity;
    }

    /**
     * {@code code} を {@code org_{orgId}_{rand8}} 形式で生成する。
     * UUID の先頭 8 文字を採用する。1 組織あたり 20 個上限のため衝突確率は事実上ゼロ。
     */
    private String generateCode(Long orgId) {
        String suffix = UUID.randomUUID().toString().substring(0, CODE_RANDOM_LENGTH);
        return "org_" + orgId + "_" + suffix;
    }

    /**
     * {@code cardNumberRegex} が指定されている場合、構文として有効か検証する。
     * 不正な場合は {@link CommonErrorCode#COMMON_001} (400 Bad Request) を投げる。
     */
    private void validateRegex(String regex) {
        if (regex == null || regex.isBlank()) {
            return;
        }
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            log.warn("無効な cardNumberRegex を拒否: regex={}, reason={}", regex, e.getMessage());
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
    }

    /**
     * プロバイダー操作（CREATED / UPDATED / DEACTIVATED）の監査ログを記録する。
     * metadata には {@code provider_id / organization_id / display_name} のみ含める。
     */
    private void recordProviderAudit(String eventType, Long userId, Long orgId,
                                     PointCardProviderEntity entity) {
        String escapedDisplayName = escapeJsonString(entity.getDisplayName());
        String metadata = String.format(
                "{\"provider_id\":\"%s\",\"organization_id\":%d,\"display_name\":\"%s\"}",
                entity.getId(), orgId, escapedDisplayName);
        auditLogService.record(
                eventType, userId,
                null, null, orgId,
                null, null, null,
                metadata);
    }

    /**
     * JSON 文字列に埋め込む値のうち、最低限の制御文字をエスケープする。
     * displayName は @Size max=100 の単純な文字列想定で、フル JSON エンコーダは過剰。
     */
    private static String escapeJsonString(String value) {
        if (value == null) return "";
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
