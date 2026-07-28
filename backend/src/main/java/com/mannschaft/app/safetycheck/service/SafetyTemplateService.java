package com.mannschaft.app.safetycheck.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.safetycheck.SafetyCheckErrorCode;
import com.mannschaft.app.safetycheck.SafetyCheckMapper;
import com.mannschaft.app.safetycheck.SafetyCheckScopeType;
import com.mannschaft.app.safetycheck.dto.CreateTemplateRequest;
import com.mannschaft.app.safetycheck.dto.SafetyTemplateResponse;
import com.mannschaft.app.safetycheck.dto.UpdateTemplateRequest;
import com.mannschaft.app.safetycheck.entity.SafetyCheckTemplateEntity;
import com.mannschaft.app.safetycheck.repository.SafetyCheckTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 安否確認テンプレートサービス。テンプレートのCRUDを担当する。
 *
 * <h2>二系統の入口（重要）</h2>
 * <p>本サービスは 2 つの Controller から呼ばれ、認可の掛かり方が異なる。</p>
 * <ul>
 *   <li><b>{@code SafetyAdminController}</b>（{@code /api/v1/system-admin/safety-checks/**}）:
 *       {@code SecurityConfig} のパス単位認可で SYSTEM_ADMIN に予約される。全テンプレート
 *       （システム既定＝スコープ null を含む）の CRUD を担う。素の
 *       {@link #createTemplate} / {@link #updateTemplate} / {@link #deleteTemplate} /
 *       {@link #listAllTemplates} がこの入口用。</li>
 *   <li><b>{@code SafetyTemplateController}</b>（{@code /api/v1/safety-checks/templates}）:
 *       パス単位認可が無い素の認証済みパス。チーム／組織が自スコープのテンプレートを
 *       自己管理するための入口であり、必ず {@code *Scoped} 系メソッド
 *       （{@link #listScopedTemplates} / {@link #getScopedTemplate} /
 *       {@link #createScopedTemplate} / {@link #updateScopedTemplate}）を経由させ、
 *       スコープ認可を通すこと。</li>
 * </ul>
 *
 * <p>素のメソッドをスコープ入口から直接呼ぶと、スコープ null（＝全ユーザーに配られる
 * システム既定テンプレート）の作成・改変が誰でも可能になるため禁止する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SafetyTemplateService {

    private final SafetyCheckTemplateRepository templateRepository;
    private final SafetyCheckMapper mapper;
    private final AccessControlService accessControlService;

    /**
     * 利用可能なテンプレート一覧を取得する（スコープ別 + システムデフォルト）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return テンプレート一覧
     */
    public List<SafetyTemplateResponse> listTemplates(String scopeType, Long scopeId) {
        SafetyCheckScopeType scope = SafetyCheckScopeType.valueOf(scopeType);
        List<SafetyCheckTemplateEntity> entities = templateRepository.findAvailableTemplates(scope, scopeId);
        return mapper.toTemplateResponseList(entities);
    }

    /**
     * テンプレート詳細を取得する。
     *
     * @param templateId テンプレートID
     * @return テンプレート詳細
     */
    public SafetyTemplateResponse getTemplate(Long templateId) {
        SafetyCheckTemplateEntity entity = findTemplateOrThrow(templateId);
        return mapper.toTemplateResponse(entity);
    }

    /**
     * テンプレートを作成する。
     *
     * @param req    作成リクエスト
     * @param userId 作成者ID
     * @return 作成されたテンプレート
     */
    @Transactional
    public SafetyTemplateResponse createTemplate(CreateTemplateRequest req, Long userId) {
        SafetyCheckScopeType scopeType = req.getScopeType() != null
                ? SafetyCheckScopeType.valueOf(req.getScopeType()) : null;

        SafetyCheckTemplateEntity entity = SafetyCheckTemplateEntity.builder()
                .scopeType(scopeType)
                .scopeId(req.getScopeId())
                .templateName(req.getTemplateName())
                .title(req.getTitle())
                .message(req.getMessage())
                .reminderIntervalMinutes(req.getReminderIntervalMinutes())
                .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                .createdBy(userId)
                .build();

        entity = templateRepository.save(entity);
        log.info("テンプレート作成: id={}, name={}", entity.getId(), entity.getTemplateName());
        return mapper.toTemplateResponse(entity);
    }

    /**
     * テンプレートを更新する。
     *
     * @param templateId テンプレートID
     * @param req        更新リクエスト
     * @return 更新されたテンプレート
     */
    @Transactional
    public SafetyTemplateResponse updateTemplate(Long templateId, UpdateTemplateRequest req) {
        SafetyCheckTemplateEntity entity = findTemplateOrThrow(templateId);

        entity.update(req.getTemplateName(), req.getTitle(), req.getMessage(),
                req.getReminderIntervalMinutes(), req.getSortOrder());
        entity = templateRepository.save(entity);

        log.info("テンプレート更新: id={}", templateId);
        return mapper.toTemplateResponse(entity);
    }

    /**
     * テンプレートを削除する。
     *
     * @param templateId テンプレートID
     */
    @Transactional
    public void deleteTemplate(Long templateId) {
        SafetyCheckTemplateEntity entity = findTemplateOrThrow(templateId);
        templateRepository.delete(entity);
        log.info("テンプレート削除: id={}", templateId);
    }

    /**
     * 全テンプレート一覧を取得する（管理者用）。
     *
     * @return テンプレート一覧
     */
    public List<SafetyTemplateResponse> listAllTemplates() {
        List<SafetyCheckTemplateEntity> entities = templateRepository.findAllByOrderBySortOrderAsc();
        return mapper.toTemplateResponseList(entities);
    }

    // ========================================
    // スコープ入口（/api/v1/safety-checks/templates）— スコープ認可つき
    // ========================================

    /**
     * 利用可能なテンプレート一覧を取得する（スコープ入口）。
     *
     * <p><b>認可</b>: 宣言スコープのメンバーのみ。テンプレート本文は当該団体の運用文言であり、
     * 非メンバーに開示しない。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    操作者ID
     * @return テンプレート一覧（スコープ別＋システム既定）
     */
    public List<SafetyTemplateResponse> listScopedTemplates(String scopeType, Long scopeId, Long userId) {
        SafetyCheckScopeType scope = parseScopeType(scopeType);
        requireScopeMember(userId, scope, scopeId);
        return listTemplates(scopeType, scopeId);
    }

    /**
     * テンプレート詳細を取得する（スコープ入口）。
     *
     * <p><b>認可（BOLA 封鎖）</b>: bare id EP のため entity を fetch し
     * <b>entity 由来のスコープ</b>でメンバーシップを判定する。権限が無ければ
     * {@code TEMPLATE_NOT_FOUND}（404）で存在を秘匿する。スコープ null のシステム既定
     * テンプレートは全スコープの一覧に載る共通文言のため、認証済みユーザーに開示してよい。</p>
     *
     * @param templateId テンプレートID
     * @param userId     操作者ID
     * @return テンプレート詳細
     */
    public SafetyTemplateResponse getScopedTemplate(Long templateId, Long userId) {
        SafetyCheckTemplateEntity entity = findTemplateOrThrow(templateId);
        // 番人テストの 2 ホップ制約のため accessControlService は本メソッドから直接呼ぶこと。
        if (entity.getScopeId() != null
                && (entity.getScopeType() == null
                    || entity.getScopeType() == SafetyCheckScopeType.GROUP
                    || userId == null
                    || !accessControlService.isMember(userId, entity.getScopeId(), entity.getScopeType().name()))) {
            throw new BusinessException(SafetyCheckErrorCode.TEMPLATE_NOT_FOUND);
        }
        return mapper.toTemplateResponse(entity);
    }

    /**
     * スコープ所有のテンプレートを作成する（スコープ入口）。
     *
     * <p><b>認可</b>: リクエストのスコープの ADMIN/DEPUTY_ADMIN のみ。スコープ未指定
     * （＝全ユーザー向けのシステム既定テンプレート）の作成は本入口では禁止し、
     * SYSTEM_ADMIN 専用の {@code /api/v1/system-admin/**} 入口に限定する。</p>
     *
     * @param req    作成リクエスト（scopeType / scopeId 必須）
     * @param userId 作成者ID
     * @return 作成されたテンプレート
     */
    @Transactional
    public SafetyTemplateResponse createScopedTemplate(CreateTemplateRequest req, Long userId) {
        if (req.getScopeType() == null || req.getScopeId() == null) {
            // システム既定テンプレート（スコープ null）の作成は SYSTEM_ADMIN 入口専用。
            throw new BusinessException(SafetyCheckErrorCode.ACCESS_DENIED);
        }
        SafetyCheckScopeType scope = parseScopeType(req.getScopeType());
        if (scope == SafetyCheckScopeType.GROUP || userId == null
                || !accessControlService.isAdminOrAbove(userId, req.getScopeId(), scope.name())) {
            throw new BusinessException(SafetyCheckErrorCode.ACCESS_DENIED);
        }
        return createTemplate(req, userId);
    }

    /**
     * スコープ所有のテンプレートを更新する（スコープ入口）。
     *
     * <p><b>認可（BOLA 封鎖）</b>: bare id EP のため entity を fetch し
     * <b>entity 由来のスコープ</b>で二段階に判定する。</p>
     * <ol>
     *   <li>スコープ非メンバー（部外者・別団体の ADMIN による越境）およびスコープ null
     *       （システム既定テンプレート）は {@code TEMPLATE_NOT_FOUND}（404）で存在を秘匿する。</li>
     *   <li>スコープのメンバーだが ADMIN/DEPUTY_ADMIN でない場合は 403（{@code ACCESS_DENIED}）。
     *       当該メンバーは一覧・詳細で当該テンプレートを既に閲覧できるため、存在秘匿の意味がない。</li>
     * </ol>
     *
     * @param templateId テンプレートID
     * @param req        更新リクエスト
     * @param userId     操作者ID
     * @return 更新されたテンプレート
     */
    @Transactional
    public SafetyTemplateResponse updateScopedTemplate(Long templateId, UpdateTemplateRequest req, Long userId) {
        SafetyCheckTemplateEntity entity = findTemplateOrThrow(templateId);
        // 番人テストの 2 ホップ制約のため accessControlService は本メソッドから直接呼ぶこと。
        if (entity.getScopeId() == null || entity.getScopeType() == null
                || entity.getScopeType() == SafetyCheckScopeType.GROUP
                || userId == null
                || !accessControlService.isMember(
                        userId, entity.getScopeId(), entity.getScopeType().name())) {
            throw new BusinessException(SafetyCheckErrorCode.TEMPLATE_NOT_FOUND);
        }
        if (!accessControlService.isAdminOrAbove(
                userId, entity.getScopeId(), entity.getScopeType().name())) {
            throw new BusinessException(SafetyCheckErrorCode.ACCESS_DENIED);
        }
        return updateTemplate(templateId, req);
    }

    // --- プライベートメソッド ---

    /**
     * 宣言スコープのメンバーであることを要求する。
     *
     * <p>{@code GROUP} は {@code memberships} で所属解決できないため fail-closed で拒否する
     * （{@code SafetyCheckRepository#searchByKeyword} の既存方針と同じ）。</p>
     */
    private void requireScopeMember(Long userId, SafetyCheckScopeType scope, Long scopeId) {
        if (scope == SafetyCheckScopeType.GROUP || userId == null
                || !accessControlService.isMember(userId, scopeId, scope.name())) {
            throw new BusinessException(SafetyCheckErrorCode.ACCESS_DENIED);
        }
    }

    /**
     * スコープ種別文字列をEnumに変換する。
     */
    private SafetyCheckScopeType parseScopeType(String scopeType) {
        try {
            return SafetyCheckScopeType.valueOf(scopeType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(SafetyCheckErrorCode.INVALID_SCOPE_TYPE);
        }
    }

    /**
     * テンプレートを取得する。存在しない場合は例外をスローする。
     */
    private SafetyCheckTemplateEntity findTemplateOrThrow(Long id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> new BusinessException(SafetyCheckErrorCode.TEMPLATE_NOT_FOUND));
    }
}
