package com.mannschaft.app.disclosure.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import com.mannschaft.app.disclosure.dto.DisclosureCustomTemplateRequest;
import com.mannschaft.app.disclosure.dto.DisclosureFormTemplateResponse;
import com.mannschaft.app.disclosure.entity.DisclosureFormTemplateEntity;
import com.mannschaft.app.disclosure.repository.DisclosureFormTemplateRepository;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 重要事項説明書 様式テンプレート 編集サービス（F09.14 Phase 3-C）。
 *
 * <p>設計書 §4 様式テンプレート API のうち POST / PUT / DELETE 系（カスタム様式 CRUD）を提供する。
 * 読み取り専用の {@link DisclosureFormTemplateService} とはトランザクション境界・依存関係が異なるため
 * 別クラスとして分離する（Service 層責務分割: Query / Command の CQRS 風構成）。</p>
 *
 * <h3>不変条件</h3>
 * <ul>
 *   <li>システム提供（{@code is_system_template=true}）テンプレートは編集／削除不可
 *       → {@link DisclosureErrorCode#DISCLOSURE_014}</li>
 *   <li>1 組織あたりカスタム様式は最大 {@value #MAX_CUSTOM_TEMPLATES_PER_ORG} 件
 *       → {@link DisclosureErrorCode#DISCLOSURE_013}（CHECK 制約ではなく Service 層で計数）</li>
 *   <li>更新は楽観的ロック（{@code version_lock} = @Version）。
 *       バージョン不一致時 {@link DisclosureErrorCode#DISCLOSURE_003}</li>
 *   <li>削除は物理削除でなく {@code deleted_at} で論理削除。既存ドラフトの
 *       {@code template_version_snapshot} は別カラムで保持されるため壊れない（Phase 2 方針踏襲）</li>
 *   <li>{@code code} は更新時に変更不可（ドラフトの整合性／ユニーク制約 {@code uq_dft_code_version} のため）</li>
 *   <li>更新時に {@code version} 文字列を変えても既存ドラフトはスナップショットで保護される</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class DisclosureFormTemplateEditService {

    /** 設計書 §3 disclosure_form_templates: 1 組織あたりカスタム様式の上限件数。 */
    static final int MAX_CUSTOM_TEMPLATES_PER_ORG = 10;

    /** 設計書 §3 で許容されるスコープ種別。本フェーズでは ORGANIZATION のみ。 */
    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";

    private final DisclosureFormTemplateRepository templateRepository;
    private final DisclosureFormTemplateValidator validator;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;

    // =========================================================================
    // 作成
    // =========================================================================

    /**
     * カスタム様式を新規作成する。
     *
     * <p>処理:</p>
     * <ol>
     *   <li>件数上限チェック（{@value #MAX_CUSTOM_TEMPLATES_PER_ORG} 件） →
     *       超過時 {@link DisclosureErrorCode#DISCLOSURE_013}</li>
     *   <li>{@code form_schema} 構造検証（{@link DisclosureFormTemplateValidator}）</li>
     *   <li>{@code (code, version)} 重複チェック（既存に同一コード × 同一バージョンがあれば
     *       {@link DisclosureErrorCode#DISCLOSURE_004}）</li>
     *   <li>Entity 永続化（{@code is_system_template=false}, {@code scope_type=ORGANIZATION}）</li>
     * </ol>
     *
     * @param organizationId 所属組織 ID（path variable から渡される）
     * @param userId         作成者 ID（{@code created_by}）
     * @param request        作成リクエスト
     * @return 作成された様式テンプレートのレスポンス
     */
    @Transactional
    public DisclosureFormTemplateResponse createCustomTemplate(Long organizationId, Long userId,
                                                               DisclosureCustomTemplateRequest request) {
        if (organizationId == null) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004);
        }
        // 認可根治戦役 Wave3-B4: カスタム様式作成は ADMIN/DEPUTY_ADMIN 以上のみ許可する
        accessControlService.checkAdminOrAbove(userId, organizationId, SCOPE_ORGANIZATION);

        // 1. 件数上限チェック
        long currentCount = templateRepository.countByScopeTypeAndScopeIdAndDeletedAtIsNull(
                SCOPE_ORGANIZATION, organizationId);
        if (currentCount >= MAX_CUSTOM_TEMPLATES_PER_ORG) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_013);
        }

        // 2. form_schema 検証
        validator.validate(request.formSchema());

        // 3. (code, version) 重複チェック
        templateRepository.findByCodeAndVersionAndDeletedAtIsNull(request.code(), request.version())
                .ifPresent(existing -> {
                    throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004);
                });

        // 4. 永続化
        DisclosureFormTemplateEntity entity = DisclosureFormTemplateEntity.builder()
                .code(request.code())
                .name(request.name())
                .prefectureCode(request.prefectureCode())
                .version(request.version())
                .isStandard(false)
                .isSystemTemplate(false)
                .scopeType(SCOPE_ORGANIZATION)
                .scopeId(organizationId)
                .formSchema(serializeSchema(request.formSchema()))
                .pdfTemplatePath(request.pdfTemplatePath())
                .excelTemplateKey(request.excelTemplateKey())
                .effectiveFrom(request.effectiveFrom())
                .effectiveUntil(request.effectiveUntil())
                .isActive(request.isActive() == null ? Boolean.TRUE : request.isActive())
                .createdBy(userId)
                .build();

        DisclosureFormTemplateEntity saved = templateRepository.save(entity);
        log.info("カスタム様式テンプレート作成: organizationId={}, templateId={}, code={}",
                organizationId, saved.getId(), saved.getCode());
        return DisclosureFormTemplateResponse.from(saved, request.formSchema());
    }

    // =========================================================================
    // 更新
    // =========================================================================

    /**
     * カスタム様式を更新する。
     *
     * <p>処理:</p>
     * <ol>
     *   <li>対象 Entity 取得（論理削除済は 404）</li>
     *   <li>クロステナント検証（別組織のテンプレは 403 = {@code DISCLOSURE_002}）</li>
     *   <li>システム提供チェック → 編集不可（{@link DisclosureErrorCode#DISCLOSURE_014}）</li>
     *   <li>{@code form_schema} 構造検証</li>
     *   <li>{@code versionLock}（@Version）一致検証 → 不一致時
     *       {@link DisclosureErrorCode#DISCLOSURE_003}</li>
     *   <li>{@code (code, version)} 重複チェック（自分以外）</li>
     *   <li>Entity 更新（{@code code} は変更不可。{@code version} 等は新規バージョンとして上書き保存）</li>
     * </ol>
     *
     * <p><b>既存ドラフトへの影響</b>: ドラフトは作成時の {@code template_version_snapshot}（VARCHAR）
     * を保持しているため、本テンプレートの {@code version} を更新してもスナップショットは変化せず壊れない。
     * 出力時には Service 層で {@code version} と {@code template_version_snapshot} を比較し
     * 差異があれば警告を出す（既存実装、Phase 2 方針）。</p>
     */
    @Transactional
    public DisclosureFormTemplateResponse updateCustomTemplate(Long organizationId, Long templateId,
                                                               Long userId,
                                                               DisclosureCustomTemplateRequest request) {
        if (organizationId == null || templateId == null) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004);
        }

        DisclosureFormTemplateEntity entity = templateRepository
                .findByIdAndDeletedAtIsNull(templateId)
                .orElseThrow(() -> new BusinessException(DisclosureErrorCode.DISCLOSURE_001));

        ensureCustomTemplate(entity);
        ensureSameOrganization(entity, organizationId);
        // 認可根治戦役 Wave3-B4: カスタム様式更新は ADMIN/DEPUTY_ADMIN 以上のみ許可する
        accessControlService.checkAdminOrAbove(userId, organizationId, SCOPE_ORGANIZATION);

        // 楽観的ロック検査（事前チェック。saveAndFlush でも発火するが、明示的に投げる）
        if (request.versionLock() == null) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004);
        }
        if (!request.versionLock().equals(entity.getVersionLock())) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_003);
        }

        // form_schema 検証
        validator.validate(request.formSchema());

        // code は変更不可（クライアントが誤って別 code を送ってきた場合は 400）
        if (!entity.getCode().equals(request.code())) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004);
        }

        // (code, version) 重複チェック（自分以外）
        templateRepository.findByCodeAndVersionAndDeletedAtIsNull(request.code(), request.version())
                .filter(other -> !other.getId().equals(entity.getId()))
                .ifPresent(other -> {
                    throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004);
                });

        // Entity の更新メソッド経由で managed entity 自身を書き換える。
        // 旧実装は最後に entity.toBuilder().build() で新インスタンスを生成し saveAndFlush していたが、
        // 同一 ID の managed entity が既に PersistenceContext にいる状態で merge 経路に乗るため
        // @Version (versionLock) のインクリメントが期待通り発火しなかった
        // （F09.14 Phase 3-G 根治治療: F-2 PR #521 統合テストで検出）。
        // managed entity 直接更新 → dirty checking → UPDATE で version_lock +1 が確実に走る。
        entity.rename(request.name());
        entity.updateFormSchema(serializeSchema(request.formSchema()));
        entity.updateEffectivePeriod(request.effectiveFrom(), request.effectiveUntil());
        if (request.isActive() != null) {
            entity.changeActive(request.isActive());
        }
        entity.updateMetadata(
                request.version(),
                request.prefectureCode(),
                request.pdfTemplatePath(),
                request.excelTemplateKey());

        try {
            DisclosureFormTemplateEntity saved = templateRepository.saveAndFlush(entity);
            log.info("カスタム様式テンプレート更新: organizationId={}, templateId={}, newVersion={}, versionLock={}",
                    organizationId, saved.getId(), saved.getVersion(), saved.getVersionLock());
            return DisclosureFormTemplateResponse.from(saved, request.formSchema());
        } catch (OptimisticLockException | OptimisticLockingFailureException e) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_003, e);
        }
    }

    // =========================================================================
    // 削除
    // =========================================================================

    /**
     * カスタム様式を論理削除する。物理削除は行わない（{@code deleted_at} に現在時刻をセット）。
     *
     * <p>システム提供テンプレートは削除不可（{@link DisclosureErrorCode#DISCLOSURE_014}）。
     * 別組織のテンプレートを指定した場合 {@link DisclosureErrorCode#DISCLOSURE_002}。</p>
     */
    @Transactional
    public void deleteCustomTemplate(Long organizationId, Long userId, Long templateId) {
        if (organizationId == null || templateId == null) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004);
        }

        DisclosureFormTemplateEntity entity = templateRepository
                .findByIdAndDeletedAtIsNull(templateId)
                .orElseThrow(() -> new BusinessException(DisclosureErrorCode.DISCLOSURE_001));

        ensureCustomTemplate(entity);
        ensureSameOrganization(entity, organizationId);
        // 認可根治戦役 Wave3-B4: カスタム様式削除は ADMIN/DEPUTY_ADMIN 以上のみ許可する
        accessControlService.checkAdminOrAbove(userId, organizationId, SCOPE_ORGANIZATION);

        entity.softDelete();
        templateRepository.save(entity);
        log.info("カスタム様式テンプレート削除（論理）: organizationId={}, templateId={}",
                organizationId, templateId);
    }

    // =========================================================================
    // 内部ヘルパー
    // =========================================================================

    /** システム提供テンプレートは編集不可。 */
    private void ensureCustomTemplate(DisclosureFormTemplateEntity entity) {
        if (Boolean.TRUE.equals(entity.getIsSystemTemplate())) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_014);
        }
    }

    /** クロステナント遮断: カスタムテンプレは作成組織以外から触れない。 */
    private void ensureSameOrganization(DisclosureFormTemplateEntity entity, Long organizationId) {
        if (!SCOPE_ORGANIZATION.equals(entity.getScopeType())
                || !organizationId.equals(entity.getScopeId())) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_002);
        }
    }

    /** JsonNode を JSON 文字列に直列化する。失敗時は DISCLOSURE_004 を投げる（通常は Validator 通過後なので発生しない）。 */
    private String serializeSchema(JsonNode schema) {
        try {
            return objectMapper.writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004, e);
        }
    }
}
