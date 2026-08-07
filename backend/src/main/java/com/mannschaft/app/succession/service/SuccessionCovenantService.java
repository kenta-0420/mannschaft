package com.mannschaft.app.succession.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.pdf.SignedPdfResult;
import com.mannschaft.app.common.pdf.SuccessionCovenantContext;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.resident.entity.DwellingUnitEntity;
import com.mannschaft.app.resident.entity.ResidentRegistryEntity;
import com.mannschaft.app.resident.repository.DwellingUnitRepository;
import com.mannschaft.app.resident.repository.ResidentRegistryRepository;
import com.mannschaft.app.succession.SuccessionErrorCode;
import com.mannschaft.app.succession.dto.SignCovenantRequest;
import com.mannschaft.app.succession.dto.SuccessionCovenantResponse;
import com.mannschaft.app.succession.entity.SuccessionCovenantEntity;
import com.mannschaft.app.succession.repository.SuccessionCovenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 入居時誓約サービス（F09.15 S1 第三陣B）。
 *
 * <p>設計書: {@code docs/features/F09.15_resident_succession_support.md} §6 / §7.1 / §9。
 *
 * <p>処理フロー（UC-A1）:
 * <ol>
 *   <li>居住者台帳・居室情報を取得</li>
 *   <li>同意項目（confirmedItems）のダークパターン回避チェック</li>
 *   <li>多重署名チェック（同一 residentRegistryId × covenantType で有効な誓約があれば 409）</li>
 *   <li>{@link PdfGeneratorService#generateSignedCovenantPdf} で PDF + SHA-256 + 内部署名トークン生成</li>
 *   <li>S3 へ PDF アップロード</li>
 *   <li>{@code succession_covenants} に INSERT</li>
 *   <li>監査ログ {@link AuditEventType#COVENANT_ISSUED} + {@link AuditEventType#COVENANT_SIGNED} を記録</li>
 * </ol>
 *
 * <p>F00 SUCCESSION_COVENANTS Resolver は本 Service と独立しており、
 * 本人 / ADMIN の閲覧判定は {@link com.mannschaft.app.succession.visibility.SuccessionCovenantVisibilityResolver}
 * 側で別途行う。Controller は両方を呼び分ける。
 *
 * <p>テナント分離: {@link SuccessionCovenantRepository} は {@code AbstractTenantAwareRepository}
 * を継承しており、{@code organization_id} 必須でフィルタする。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SuccessionCovenantService {

    /** S3 保存パスのプレフィックス（テナントごとに分離）。 */
    private static final String S3_KEY_PREFIX_FORMAT = "organizations/%d/succession/covenants/";

    /** 誓約 PDF の Content-Type。 */
    private static final String PDF_CONTENT_TYPE = "application/pdf";

    /** 誓約区分ごとの必須同意項目キー（ダークパターン回避）。 */
    private static final Map<String, Set<String>> REQUIRED_CONFIRMED_ITEMS = Map.of(
            "SUCCESSION_PRE_REGISTRATION", Set.of(
                    "agree_pre_registration_disclosure",
                    "agree_data_retention_10y"),
            "PRIVACY_CONSENT", Set.of(
                    "agree_personal_data_collection",
                    "agree_data_retention_10y"),
            "MONITORING_CONSENT", Set.of(
                    "agree_activity_monitoring",
                    "agree_data_retention_10y")
    );

    private final SuccessionCovenantRepository covenantRepository;
    private final ResidentRegistryRepository residentRegistryRepository;
    private final DwellingUnitRepository dwellingUnitRepository;
    private final PdfGeneratorService pdfGeneratorService;
    private final StorageService storageService;
    private final AuditLogService auditLogService;
    private final AccessControlService accessControlService;

    // ─────────────────────────────────────────────
    // 発行 + 署名（UC-A1）
    // ─────────────────────────────────────────────

    /**
     * 入居時誓約を発行・署名・保存する（F09.15 §7.1 UC-A1 一括処理）。
     *
     * <p>本人（区分所有者）が呼ぶ。同意項目を確認した上で PDF 生成・署名・保存を一括実行する。
     * 同一 {@code residentRegistryId × covenantType} で有効な誓約が既にある場合は 409 を返す。
     *
     * @param req           署名リクエスト
     * @param currentUserId 現在のユーザー ID（署名者）
     * @return 保存された誓約レスポンス
     */
    // ドメイン境界: ResidentRegistry / DwellingUnit はクロスドメインだが、誓約発行は
    // 「居住者の同意」に紐づくため Service 層で参照しても整合性は ID 経由で確保される（FK なし）。
    @Transactional
    public SuccessionCovenantResponse signCovenant(SignCovenantRequest req, Long currentUserId) {
        // 1) 居住者台帳と居室を取得
        ResidentRegistryEntity resident = residentRegistryRepository.findById(req.getResidentRegistryId())
                .orElseThrow(() -> new BusinessException(SuccessionErrorCode.RESIDENT_REGISTRY_NOT_FOUND));

        // 本人の居住者台帳であることを検証（他人の residentRegistryId を指定した
        // 代理署名・PII 混入・多重署名ロックを防ぐ。存在秘匿のため NOT_FOUND を返す）。
        if (resident.getUserId() == null || !resident.getUserId().equals(currentUserId)) {
            throw new BusinessException(SuccessionErrorCode.RESIDENT_REGISTRY_NOT_FOUND);
        }

        DwellingUnitEntity unit = dwellingUnitRepository.findById(resident.getDwellingUnitId())
                .orElseThrow(() -> new BusinessException(SuccessionErrorCode.DWELLING_UNIT_NOT_FOUND));

        Long organizationId = unit.getOrganizationId();
        if (organizationId == null) {
            // 設計書 §5.3: succession_covenants.organization_id は NOT NULL
            // 居室が組織配下にない場合は誓約発行不可
            throw new BusinessException(SuccessionErrorCode.DWELLING_UNIT_NOT_FOUND);
        }

        // 2) 同意項目チェック（ダークパターン回避）
        validateConfirmedItems(req.getCovenantType(), req.getConfirmedItems());

        // 3) 多重署名チェック（同一台帳×区分で有効な誓約が既にあれば拒否）
        List<SuccessionCovenantEntity> existing = covenantRepository
                .findByResidentRegistryIdAndCovenantTypeAndRevokedAtIsNullAndDeletedAtIsNull(
                        resident.getId(), req.getCovenantType());
        if (!existing.isEmpty()) {
            throw new BusinessException(SuccessionErrorCode.COVENANT_ALREADY_SIGNED);
        }

        // 4) PDF 生成コンテキスト組み立て
        // 注意: ResidentRegistry の lastName / firstName は @Convert で復号済み（PII）。
        // 設計書 §9.1 に従い、PDF 生成時のみ復号値を扱い、保存はしない。
        LocalDateTime signedAt = LocalDateTime.now();
        // subjectId は PDF 生成前は仮（UUIDv7 はサーバ採番されるため）。
        // 一段目で仮 ID（UUID.randomUUID）を使い、PDF 内のサブジェクト識別子としては
        // 「最終的に Entity に保存される ID」と一致させたいが、Hibernate の UuidGenerator は
        // INSERT 時に採番するため事前確定できない。そのため、暫定的に「テンプレ生成用の
        // subjectId は UUIDv7 を事前生成し、Entity 構築時に同 ID を明示セットする」運用とする。
        // → UuidV7Entity は @GeneratedValue 自動採番のため明示セット不可。
        // 代替として subjectId はサーバ採番 UUID を tmp で渡し、保存後に DB 採番 ID を使う形にすると
        // PDF 内 ID と DB ID が不一致になる問題が発生する。
        // 本実装では「一段目 PDF はテンプレ仮 ID → 保存 → 保存後に最終 PDF 再生成 → S3 upload」の
        // 二段構成で整合させる方針も考慮したが、S1 第三陣B では PDF 内の subjectId は
        // "residentRegistryId-covenantType-timestamp" のコンポジット文字列とし、DB 主キーとは独立して
        // 内部署名トークンの subject として使う設計とする（設計書 §9.4 の内部署名トークン仕様と整合）。
        String subjectId = composeSubjectId(resident.getId(), req.getCovenantType(), signedAt);

        SuccessionCovenantContext ctx = new SuccessionCovenantContext(
                subjectId,
                req.getCovenantType(),
                covenantTypeLabel(req.getCovenantType()),
                req.getCovenantVersion(),
                resident.getLastName() + " " + resident.getFirstName(),
                unit.getUnitNumber() + " 号室",
                resident.getResidentType(),
                resident.getMoveInDate() != null ? resident.getMoveInDate() : LocalDate.now(),
                signedAt,
                organizationName(organizationId),
                ""  // representativeName は将来 OrganizationService 経由で取得（v1 では空）
        );

        SignedPdfResult signedPdf = pdfGeneratorService.generateSignedCovenantPdf(ctx);

        // 5) Entity を構築 → save で UUIDv7 主キー採番
        SuccessionCovenantEntity entity = SuccessionCovenantEntity.builder()
                .organizationId(organizationId)
                .dwellingUnitId(unit.getId())
                .residentRegistryId(resident.getId())
                .signerUserId(currentUserId)
                .covenantType(req.getCovenantType())
                .covenantVersion(req.getCovenantVersion())
                .pdfS3Key("__placeholder__")  // 保存後に確定する S3 キーで update
                .pdfSha256(signedPdf.hashSha256())
                .internalSignatureToken(signedPdf.timestampToken())
                .signedAt(signedAt)
                .build();
        SuccessionCovenantEntity saved = covenantRepository.save(entity);

        // 6) S3 キーを id 確定後に決定して PDF をアップロード
        String s3Key = buildS3Key(organizationId, saved.getId());
        storageService.upload(s3Key, signedPdf.pdf(), PDF_CONTENT_TYPE);
        saved.setPdfS3Key(s3Key);
        SuccessionCovenantEntity persisted = covenantRepository.save(saved);

        // 7) 監査ログ（COVENANT_ISSUED + COVENANT_SIGNED を 2 段で記録）
        String metadata = String.format(
                "{\"source\":\"SUCCESSION_COVENANT\",\"covenantId\":\"%s\","
                        + "\"covenantType\":\"%s\",\"covenantVersion\":\"%s\","
                        + "\"residentRegistryId\":%d,\"dwellingUnitId\":%d,"
                        + "\"pdfSha256\":\"%s\",\"signedAtEpochMs\":%d}",
                persisted.getId(),
                persisted.getCovenantType(),
                persisted.getCovenantVersion(),
                persisted.getResidentRegistryId(),
                persisted.getDwellingUnitId(),
                persisted.getPdfSha256(),
                signedPdf.signedAt().toEpochMilli());
        auditLogService.record(
                AuditEventType.COVENANT_ISSUED.name(),
                currentUserId,
                null, null, organizationId, null, null, null, metadata);
        auditLogService.record(
                AuditEventType.COVENANT_SIGNED.name(),
                currentUserId,
                null, null, organizationId, null, null, null, metadata);

        log.info("入居時誓約署名完了: covenantId={}, covenantType={}, residentRegistryId={}, organizationId={}",
                persisted.getId(), persisted.getCovenantType(), persisted.getResidentRegistryId(), organizationId);

        return toResponse(persisted);
    }

    // ─────────────────────────────────────────────
    // 撤回（本人のみ）
    // ─────────────────────────────────────────────

    /**
     * 誓約を撤回する（本人のみ）。
     *
     * @param covenantId    誓約 ID
     * @param currentUserId 操作ユーザー
     * @return 撤回後のレスポンス
     */
    @Transactional
    public SuccessionCovenantResponse revokeCovenant(UUID covenantId, Long currentUserId) {
        SuccessionCovenantEntity entity = covenantRepository.findById(covenantId)
                .orElseThrow(() -> new BusinessException(SuccessionErrorCode.COVENANT_NOT_FOUND));

        // 本人のみ撤回可
        if (!entity.getSignerUserId().equals(currentUserId)) {
            throw new BusinessException(SuccessionErrorCode.COVENANT_FORBIDDEN);
        }

        if (entity.getRevokedAt() != null) {
            throw new BusinessException(SuccessionErrorCode.COVENANT_ALREADY_REVOKED);
        }

        entity.revoke();
        SuccessionCovenantEntity saved = covenantRepository.save(entity);

        String metadata = String.format(
                "{\"source\":\"SUCCESSION_COVENANT\",\"covenantId\":\"%s\",\"covenantType\":\"%s\"}",
                saved.getId(), saved.getCovenantType());
        auditLogService.record(
                AuditEventType.COVENANT_REVOKED.name(),
                currentUserId,
                null, null, saved.getOrganizationId(),
                null, null, null, metadata);

        log.info("入居時誓約撤回: covenantId={}, signerUserId={}", saved.getId(), currentUserId);
        return toResponse(saved);
    }

    // ─────────────────────────────────────────────
    // 取得
    // ─────────────────────────────────────────────

    /**
     * 単一の誓約を取得する。本人 + ADMIN/DEPUTY_ADMIN のみ可。
     *
     * @param covenantId    誓約 ID
     * @param organizationId テナント ID（テナント分離）
     * @param currentUserId 閲覧ユーザー
     */
    public SuccessionCovenantResponse getCovenant(UUID covenantId, Long organizationId, Long currentUserId) {
        SuccessionCovenantEntity entity = covenantRepository
                .findByIdAndOrganizationIdAndDeletedAtIsNull(covenantId, organizationId)
                .orElseThrow(() -> new BusinessException(SuccessionErrorCode.COVENANT_NOT_FOUND));

        boolean isSelf = entity.getSignerUserId().equals(currentUserId);
        boolean isAdmin = accessControlService.isAdminOrAbove(currentUserId, organizationId, "ORGANIZATION");
        if (!isSelf && !isAdmin) {
            throw new BusinessException(SuccessionErrorCode.COVENANT_FORBIDDEN);
        }

        return toResponse(entity);
    }

    /**
     * 組織内の誓約一覧を取得する（ADMIN のみ）。
     */
    public Page<SuccessionCovenantResponse> listOrgCovenants(
            Long organizationId, Pageable pageable, Long currentUserId) {
        if (!accessControlService.isAdminOrAbove(currentUserId, organizationId, "ORGANIZATION")) {
            throw new BusinessException(SuccessionErrorCode.COVENANT_FORBIDDEN);
        }
        return covenantRepository
                .findByOrganizationIdAndDeletedAtIsNull(organizationId, pageable)
                .map(this::toResponse);
    }

    /**
     * 本人の誓約履歴を取得する（自分自身のみ）。
     */
    public List<SuccessionCovenantResponse> listMyCovenants(Long currentUserId) {
        return covenantRepository
                .findBySignerUserIdAndDeletedAtIsNullOrderBySignedAtDesc(currentUserId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ─────────────────────────────────────────────
    // 内部ヘルパー
    // ─────────────────────────────────────────────

    private void validateConfirmedItems(String covenantType, List<String> confirmedItems) {
        Set<String> required = REQUIRED_CONFIRMED_ITEMS.get(covenantType);
        if (required == null) {
            // covenantType の Pattern バリデーションを通過していれば到達しないが二重防御
            throw new BusinessException(SuccessionErrorCode.INVALID_COVENANT_TYPE);
        }
        if (confirmedItems == null || !confirmedItems.containsAll(required)) {
            throw new BusinessException(SuccessionErrorCode.COVENANT_CONFIRMED_ITEMS_INSUFFICIENT);
        }
    }

    private String composeSubjectId(Long residentRegistryId, String covenantType, LocalDateTime signedAt) {
        return residentRegistryId + "-" + covenantType + "-"
                + signedAt.toEpochSecond(ZoneOffset.UTC);
    }

    private String buildS3Key(Long organizationId, UUID covenantId) {
        return String.format(S3_KEY_PREFIX_FORMAT, organizationId)
                + covenantId.toString() + "_signed.pdf";
    }

    private String covenantTypeLabel(String covenantType) {
        return switch (covenantType) {
            case "SUCCESSION_PRE_REGISTRATION" -> "事前登録誓約";
            case "PRIVACY_CONSENT" -> "個人情報取扱同意";
            case "MONITORING_CONSENT" -> "見守り同意";
            default -> covenantType;
        };
    }

    /**
     * 組織名を取得する。
     * v1 では organizationId を文字列化して返す（OrganizationService への直接依存を避けるため）。
     * 将来 OrganizationService からの取得に切替。
     */
    private String organizationName(Long organizationId) {
        return "管理組合-" + organizationId;
    }

    private SuccessionCovenantResponse toResponse(SuccessionCovenantEntity e) {
        return SuccessionCovenantResponse.builder()
                .id(e.getId())
                .organizationId(e.getOrganizationId())
                .dwellingUnitId(e.getDwellingUnitId())
                .residentRegistryId(e.getResidentRegistryId())
                .signerUserId(e.getSignerUserId())
                .covenantType(e.getCovenantType())
                .covenantVersion(e.getCovenantVersion())
                .pdfS3Key(e.getPdfS3Key())
                .pdfSha256(e.getPdfSha256())
                .internalSignatureToken(e.getInternalSignatureToken())
                .signedAt(e.getSignedAt())
                .revokedAt(e.getRevokedAt())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
