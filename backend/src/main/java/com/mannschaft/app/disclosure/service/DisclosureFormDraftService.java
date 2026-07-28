package com.mannschaft.app.disclosure.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import com.mannschaft.app.disclosure.DraftStatus;
import com.mannschaft.app.disclosure.autofill.AutoFillContext;
import com.mannschaft.app.disclosure.dto.DisclosureFormDraftRequest;
import com.mannschaft.app.disclosure.dto.DisclosureFormDraftResponse;
import com.mannschaft.app.disclosure.entity.DisclosureFormDraftEntity;
import com.mannschaft.app.disclosure.entity.DisclosureFormTemplateEntity;
import com.mannschaft.app.disclosure.repository.DisclosureFormDraftRepository;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 重要事項説明書 ドラフト サービス（F09.14 Phase 2-β-4）。
 *
 * <p>設計書 §4 ドラフト API および §5.1 ドラフト作成 → 出力フローに対応。
 * 楽観的ロックは {@link DisclosureFormDraftEntity#getVersion()} で実施。
 * スコープあたり 50 件上限を超えた場合、最も古い未削除ドラフトを論理削除する。</p>
 *
 * <p>本フェーズではスコープ種別 {@code ORGANIZATION} のみサポートする。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class DisclosureFormDraftService {

    /** 設計書 §3 で許容されるスコープ種別。 */
    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";

    /** 設計書 §3 disclosure_form_drafts: スコープあたりのドラフト保持上限。 */
    static final int MAX_DRAFTS_PER_SCOPE = 50;

    private final DisclosureFormDraftRepository draftRepository;
    private final DisclosureFormTemplateService templateService;
    private final DisclosureAutoFillService autoFillService;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;

    // =========================================================================
    // 取得
    // =========================================================================

    /**
     * ドラフト詳細を取得する。スコープ不一致は 404 相当（DISCLOSURE_002・存在秘匿）。
     *
     * <p>認可根治戦役 Wave3-B4: entity 由来 scope の一致検証（BOLA ガード）に加え、
     * 呼び出し元が当該組織のメンバーであることを {@code checkMembership} で検証する
     * （従来は認証済みなら任意組織の draftId を推測して閲覧できた）。</p>
     */
    public DisclosureFormDraftResponse get(Long scopeId, Long userId, Long draftId) {
        DisclosureFormDraftEntity entity = findDraftOrThrow(draftId);
        ensureScope(entity, scopeId);
        accessControlService.checkMembership(userId, scopeId, SCOPE_ORGANIZATION);
        return toResponse(entity);
    }

    /**
     * ドラフト一覧をページング取得する。
     */
    public Page<DisclosureFormDraftResponse> list(Long scopeId, Long userId, DraftStatus status, Pageable pageable) {
        accessControlService.checkMembership(userId, scopeId, SCOPE_ORGANIZATION);
        Pageable safePageable = pageable != null ? pageable : PageRequest.of(0, 20);
        Page<DisclosureFormDraftEntity> page = (status != null)
                ? draftRepository.findByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNull(
                        SCOPE_ORGANIZATION, scopeId, status, safePageable)
                : draftRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(
                        SCOPE_ORGANIZATION, scopeId, safePageable);
        return page.map(this::toResponse);
    }

    /**
     * Entity を取得する（他 Service から呼ぶ用）。論理削除済は 404。
     */
    public DisclosureFormDraftEntity findDraftOrThrow(Long draftId) {
        return draftRepository.findByIdAndDeletedAtIsNull(draftId)
                .orElseThrow(() -> new BusinessException(DisclosureErrorCode.DISCLOSURE_001));
    }

    // =========================================================================
    // 作成 / 更新 / 削除
    // =========================================================================

    /**
     * ドラフトを新規作成する。
     *
     * <p>処理:</p>
     * <ol>
     *   <li>{@code templateId} の存在確認 + 当該組織から閲覧可能か検証</li>
     *   <li>50 件上限チェック（超過分は古いものから自動論理削除）</li>
     *   <li>Entity 永続化（{@code template_version_snapshot} = 様式の最新 version）</li>
     * </ol>
     */
    @Transactional
    public DisclosureFormDraftResponse create(Long scopeId, Long userId, DisclosureFormDraftRequest request) {
        accessControlService.checkAdminOrAbove(userId, scopeId, SCOPE_ORGANIZATION);
        if (request.templateId() == null) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004);
        }
        // テンプレート存在 + クロステナント検証（templateService 側で 002/001 を投げる）
        DisclosureFormTemplateEntity template = templateService.getEntityOrThrow(request.templateId());
        ensureTemplateVisibleToScope(template, scopeId);
        // effective_until 経過済テンプレートは新規作成時点で弾く（DISCLOSURE_006）
        if (template.getEffectiveUntil() != null
                && template.getEffectiveUntil().isBefore(java.time.LocalDate.now())) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_006);
        }

        enforceMaxDraftsLimit(scopeId);

        String formDataJson = serializeOrEmpty(request.formData());

        DisclosureFormDraftEntity entity = DisclosureFormDraftEntity.builder()
                .scopeType(SCOPE_ORGANIZATION)
                .scopeId(scopeId)
                .templateId(template.getId())
                .templateVersionSnapshot(template.getVersion())
                .title(request.title())
                .targetDwellingUnitId(request.targetDwellingUnitId())
                .formData(formDataJson)
                .referencedPackageIds(null)
                .status(DraftStatus.DRAFT)
                .createdBy(userId)
                .updatedBy(userId)
                .build();

        DisclosureFormDraftEntity saved = draftRepository.save(entity);
        log.info("重説書ドラフト作成: scopeId={}, draftId={}, templateId={}",
                scopeId, saved.getId(), template.getId());
        return toResponse(saved);
    }

    /**
     * ドラフトを更新する（楽観的ロック）。タイトル / formData / targetDwellingUnitId のみ変更可。
     * EXPORTED ステータスは更新不可。
     *
     * @param request クライアント側で保持している {@code version} を必ず指定する
     */
    @Transactional
    public DisclosureFormDraftResponse update(Long scopeId, Long draftId, Long userId,
                                              DisclosureFormDraftRequest request) {
        DisclosureFormDraftEntity entity = findDraftOrThrow(draftId);
        ensureScope(entity, scopeId);
        accessControlService.checkAdminOrAbove(userId, scopeId, SCOPE_ORGANIZATION);
        ensureMutable(entity);

        if (request.version() == null) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004);
        }
        if (!request.version().equals(entity.getVersion())) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_003);
        }

        entity.rename(request.title());
        if (request.targetDwellingUnitId() != null) {
            entity.assignDwellingUnit(request.targetDwellingUnitId());
        }
        if (request.formData() != null) {
            entity.updateFormData(serializeOrEmpty(request.formData()));
        }
        entity.recordUpdatedBy(userId);

        try {
            DisclosureFormDraftEntity saved = draftRepository.saveAndFlush(entity);
            return toResponse(saved);
        } catch (OptimisticLockException | OptimisticLockingFailureException e) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_003, e);
        }
    }

    /**
     * 自動引用データを再取得して formData にマージする（手動入力済の値は上書きしない）。
     * 設計書 §5.1 step 5 の挙動。
     */
    @Transactional
    public DisclosureFormDraftResponse refreshAutoFill(Long scopeId, Long draftId, Long userId,
                                                       boolean allowPersonalInfo) {
        DisclosureFormDraftEntity entity = findDraftOrThrow(draftId);
        ensureScope(entity, scopeId);
        accessControlService.checkAdminOrAbove(userId, scopeId, SCOPE_ORGANIZATION);
        ensureMutable(entity);

        DisclosureFormTemplateEntity template = templateService.getEntityOrThrow(entity.getTemplateId());
        JsonNode formSchema = parseJsonOrNull(template.getFormSchema());
        if (formSchema == null) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004);
        }

        AutoFillContext context = new AutoFillContext(
                SCOPE_ORGANIZATION, scopeId,
                entity.getTargetDwellingUnitId(),
                allowPersonalInfo,
                Map.of());
        Map<String, Object> autoFilled = autoFillService.autoFillAll(formSchema, context);

        // 既存 formData にマージ（既存値があるフィールドは上書きしない）
        com.fasterxml.jackson.databind.node.ObjectNode merged = mergeAutoFill(
                parseJsonOrNull(entity.getFormData()), autoFilled);
        entity.updateFormData(serializeOrEmpty(merged));
        entity.recordUpdatedBy(userId);

        DisclosureFormDraftEntity saved = draftRepository.save(entity);
        log.info("重説書ドラフト自動引用更新: scopeId={}, draftId={}, fields={}",
                scopeId, draftId, autoFilled.size());
        return toResponse(saved);
    }

    /**
     * ドラフトを論理削除する。EXPORTED 済みも履歴管理上残しつつ削除可能。
     */
    @Transactional
    public void delete(Long scopeId, Long userId, Long draftId) {
        DisclosureFormDraftEntity entity = findDraftOrThrow(draftId);
        ensureScope(entity, scopeId);
        accessControlService.checkAdminOrAbove(userId, scopeId, SCOPE_ORGANIZATION);
        entity.softDelete();
        draftRepository.save(entity);
        log.info("重説書ドラフト削除: scopeId={}, draftId={}", scopeId, draftId);
    }

    /**
     * 出力後の状態遷移用（DisclosureExportService から呼ぶ）。
     */
    @Transactional
    public void markExported(DisclosureFormDraftEntity entity, Long userId) {
        entity.changeStatus(DraftStatus.EXPORTED);
        entity.recordUpdatedBy(userId);
        draftRepository.save(entity);
    }

    /**
     * 引用済みパッケージ ID を保存する（出力時に DisclosureExportService から呼ぶ）。
     */
    @Transactional
    public void recordReferencedPackages(DisclosureFormDraftEntity entity, List<Long> packageIds) {
        if (packageIds == null) {
            return;
        }
        try {
            entity.updateReferencedPackageIds(objectMapper.writeValueAsString(packageIds));
            draftRepository.save(entity);
        } catch (JsonProcessingException e) {
            log.warn("referenced_package_ids シリアライズ失敗: draftId={}", entity.getId(), e);
        }
    }

    // =========================================================================
    // 内部ヘルパー
    // =========================================================================

    DisclosureFormDraftResponse toResponse(DisclosureFormDraftEntity entity) {
        return DisclosureFormDraftResponse.from(
                entity,
                parseJsonOrNull(entity.getFormData()),
                parseJsonOrNull(entity.getReferencedPackageIds()));
    }

    private void ensureScope(DisclosureFormDraftEntity entity, Long scopeId) {
        if (!SCOPE_ORGANIZATION.equals(entity.getScopeType())
                || !entity.getScopeId().equals(scopeId)) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_002);
        }
    }

    private void ensureMutable(DisclosureFormDraftEntity entity) {
        if (entity.getStatus() == DraftStatus.EXPORTED) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004);
        }
    }

    /** テンプレートが当該スコープから利用可能か検証する。 */
    private void ensureTemplateVisibleToScope(DisclosureFormTemplateEntity template, Long scopeId) {
        if (Boolean.TRUE.equals(template.getIsSystemTemplate())) {
            return; // システム提供は全組織から利用可
        }
        if (!SCOPE_ORGANIZATION.equals(template.getScopeType())
                || !scopeId.equals(template.getScopeId())) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_002);
        }
    }

    /**
     * スコープあたり {@value #MAX_DRAFTS_PER_SCOPE} 件を超えないよう、最古ドラフトを論理削除する。
     */
    private void enforceMaxDraftsLimit(Long scopeId) {
        long count = draftRepository.countByScopeTypeAndScopeIdAndDeletedAtIsNull(
                SCOPE_ORGANIZATION, scopeId);
        if (count < MAX_DRAFTS_PER_SCOPE) {
            return;
        }
        long excess = count - MAX_DRAFTS_PER_SCOPE + 1;
        List<DisclosureFormDraftEntity> oldest = draftRepository
                .findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByUpdatedAtAsc(
                        SCOPE_ORGANIZATION, scopeId,
                        PageRequest.of(0, (int) Math.min(excess, Integer.MAX_VALUE)));
        for (DisclosureFormDraftEntity old : oldest) {
            old.softDelete();
            draftRepository.save(old);
            log.info("重説書ドラフト自動論理削除（上限到達）: scopeId={}, draftId={}",
                    scopeId, old.getId());
        }
    }

    private String serializeOrEmpty(JsonNode node) {
        if (node == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004, e);
        }
    }

    private String serializeOrEmpty(Object obj) {
        if (obj == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004, e);
        }
    }

    private JsonNode parseJsonOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            log.warn("JSON パース失敗（破損カラムを skip）: {}", e.getOriginalMessage());
            return null;
        }
    }

    /** 既存 formData に自動引用結果をマージ。既存値が non-null なフィールドは上書きしない。 */
    private com.fasterxml.jackson.databind.node.ObjectNode mergeAutoFill(
            JsonNode existing, Map<String, Object> autoFilled) {
        com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
        if (existing != null && existing.isObject()) {
            existing.fields().forEachRemaining(e -> result.set(e.getKey(), e.getValue()));
        }
        for (Map.Entry<String, Object> e : autoFilled.entrySet()) {
            JsonNode current = result.get(e.getKey());
            if (current == null || current.isNull()
                    || (current.isTextual() && current.asText().isBlank())) {
                result.set(e.getKey(), objectMapper.valueToTree(e.getValue()));
            }
        }
        return result;
    }
}
