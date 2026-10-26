package com.mannschaft.app.disclosure.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import com.mannschaft.app.disclosure.dto.DisclosureFormTemplateResponse;
import com.mannschaft.app.disclosure.entity.DisclosureFormTemplateEntity;
import com.mannschaft.app.disclosure.repository.DisclosureFormTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重要事項説明書 様式テンプレート 読み取り専用サービス（F09.14 Phase 2-β-4）。
 *
 * <p>設計書 §4 様式テンプレート API のうち GET 系（一覧 / 詳細）を提供する。
 * 作成 / 更新 / 削除は Phase 3 のカスタム様式管理で別途実装する想定のため、本フェーズでは未提供。</p>
 *
 * <p>システム提供（{@code is_system_template=true}）と組織カスタム
 * （{@code scope_type=ORGANIZATION}）を統合した一覧を返却する。組織カスタムは
 * 当該組織のみ可視（クロステナント遮断）。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class DisclosureFormTemplateService {

    /** 設計書 §3 で許容されるスコープ種別。本フェーズでは ORGANIZATION のみ。 */
    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";

    private final DisclosureFormTemplateRepository templateRepository;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;

    /**
     * 利用可能様式の一覧を取得する。
     *
     * <p>システム提供のアクティブ様式 + 当該組織のカスタム様式を統合して返す。
     * {@code prefectureCode} 指定時は当該都道府県および全国共通（NULL）のみに絞り込む。</p>
     *
     * @param scopeType       スコープ種別（ORGANIZATION のみ許容、null 時は全国共通のみ返す）
     * @param scopeId         組織 ID
     * @param prefectureCode  JIS 都道府県コード（NULL 可）
     * @return 利用可能様式のレスポンスリスト（重複は ID で排除）
     */
    public List<DisclosureFormTemplateResponse> listAvailable(String scopeType, Long scopeId, Long userId,
                                                              String prefectureCode) {
        validateScope(scopeType);
        if (SCOPE_ORGANIZATION.equals(scopeType) && scopeId != null) {
            accessControlService.checkMembership(userId, scopeId, SCOPE_ORGANIZATION);
        }

        // 1. システム提供 / 都道府県共通アクティブ様式
        List<DisclosureFormTemplateEntity> base =
                templateRepository.findActiveByPrefecture(prefectureCode);

        // 2. 組織カスタム
        Map<Long, DisclosureFormTemplateEntity> merged = new LinkedHashMap<>();
        for (DisclosureFormTemplateEntity t : base) {
            // システム提供 (is_system_template=true) または scopeType が NULL のもののみ採用
            if (Boolean.TRUE.equals(t.getIsSystemTemplate()) || t.getScopeType() == null) {
                merged.put(t.getId(), t);
            }
        }
        if (SCOPE_ORGANIZATION.equals(scopeType) && scopeId != null) {
            // findActiveByPrefecture は組織カスタムも含むため、別組織のものを除外
            templateRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(
                            scopeType, scopeId, org.springframework.data.domain.Pageable.unpaged())
                    .forEach(t -> {
                        if (Boolean.TRUE.equals(t.getIsActive())) {
                            merged.put(t.getId(), t);
                        }
                    });
        }

        List<DisclosureFormTemplateResponse> result = new ArrayList<>(merged.size());
        for (DisclosureFormTemplateEntity t : merged.values()) {
            result.add(DisclosureFormTemplateResponse.from(t, parseSchema(t.getFormSchema())));
        }
        return result;
    }

    /**
     * 様式テンプレート詳細を取得する。
     *
     * <p>カスタムテンプレートの場合、当該組織以外からは取得不可（403 相当）。
     * 本フェーズでは Repository 層で scope 検証を兼ねるが、API 経由で他組織 ID
     * を指定された場合の漏洩を防ぐため Service 層でも検証する。</p>
     */
    public DisclosureFormTemplateResponse get(String scopeType, Long scopeId, Long userId, Long templateId) {
        validateScope(scopeType);
        if (SCOPE_ORGANIZATION.equals(scopeType) && scopeId != null) {
            accessControlService.checkMembership(userId, scopeId, SCOPE_ORGANIZATION);
        }
        DisclosureFormTemplateEntity entity = templateRepository
                .findByIdAndDeletedAtIsNull(templateId)
                .orElseThrow(() -> new BusinessException(DisclosureErrorCode.DISCLOSURE_001));

        // クロステナント遮断: カスタムテンプレートは作成組織内のみ閲覧可
        if (Boolean.FALSE.equals(entity.getIsSystemTemplate())) {
            if (!SCOPE_ORGANIZATION.equals(entity.getScopeType())
                    || !scopeId.equals(entity.getScopeId())) {
                throw new BusinessException(DisclosureErrorCode.DISCLOSURE_002);
            }
        }
        return DisclosureFormTemplateResponse.from(entity, parseSchema(entity.getFormSchema()));
    }

    /**
     * Service 内部での Entity 取得（他 Service から呼ぶ用）。論理削除済は 404。
     */
    public DisclosureFormTemplateEntity getEntityOrThrow(Long templateId) {
        return templateRepository.findByIdAndDeletedAtIsNull(templateId)
                .orElseThrow(() -> new BusinessException(DisclosureErrorCode.DISCLOSURE_001));
    }

    /**
     * form_schema JSON 文字列を JsonNode へパースする。失敗時は {@link DisclosureErrorCode#DISCLOSURE_004}
     * を投げず、null を返してフロント側のエラーハンドリングに任せる
     * （管理者用画面ではテンプレート作成時にバリデータが検査済のため通常発生しない）。
     */
    private JsonNode parseSchema(String formSchemaJson) {
        if (formSchemaJson == null || formSchemaJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(formSchemaJson);
        } catch (JsonProcessingException e) {
            log.warn("form_schema の JSON パースに失敗: {}", e.getOriginalMessage());
            return null;
        }
    }

    private void validateScope(String scopeType) {
        if (scopeType != null && !SCOPE_ORGANIZATION.equals(scopeType)) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004);
        }
    }
}
