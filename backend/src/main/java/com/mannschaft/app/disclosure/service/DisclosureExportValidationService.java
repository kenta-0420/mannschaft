package com.mannschaft.app.disclosure.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import com.mannschaft.app.disclosure.entity.DisclosureFormDraftEntity;
import com.mannschaft.app.disclosure.entity.DisclosureFormTemplateEntity;
import com.mannschaft.app.property.entity.PropertyWorkPackageEntity;
import com.mannschaft.app.property.repository.PropertyWorkPackageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 重要事項説明書 出力前のバリデーション専任サービス（F09.14 Phase 4-A リファクタリング第 4 弾）。
 *
 * <p>{@link DisclosureExportService} ファサードから委譲される検証ロジックを集約する。
 * 主な責務:</p>
 * <ul>
 *   <li>スコープ整合性 (DISCLOSURE_002)</li>
 *   <li>テンプレートバージョン整合 / 有効期限 (DISCLOSURE_006)</li>
 *   <li>form_schema の JSON パース + 必須項目チェック (DISCLOSURE_004 / DISCLOSURE_007)</li>
 *   <li>引用パッケージ整合性検証 (DISCLOSURE_008、is_disclosable=false 等は警告)</li>
 * </ul>
 *
 * <p>本クラスは読込専用。検証中に DB 書き込みは行わない。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class DisclosureExportValidationService {

    /** 設計書 §3 で許容されるスコープ種別。 */
    static final String SCOPE_ORGANIZATION = "ORGANIZATION";

    private final DisclosureFormTemplateValidator templateValidator;
    private final PropertyWorkPackageRepository propertyWorkPackageRepository;
    private final ObjectMapper objectMapper;

    /**
     * 出力対象 entity の scopeType / scopeId と期待されるスコープが一致するか確認する。
     *
     * @throws BusinessException {@link DisclosureErrorCode#DISCLOSURE_002}
     */
    public void ensureScope(String entityScopeType, Long entityScopeId, Long expectedScopeId) {
        if (!SCOPE_ORGANIZATION.equals(entityScopeType) || !entityScopeId.equals(expectedScopeId)) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_002);
        }
    }

    /**
     * ドラフトとテンプレートのバージョン整合性 / 有効期限を検証する。
     *
     * @throws BusinessException {@link DisclosureErrorCode#DISCLOSURE_006}
     */
    public void validateTemplateVersion(DisclosureFormDraftEntity draft,
                                        DisclosureFormTemplateEntity template) {
        if (!template.getVersion().equals(draft.getTemplateVersionSnapshot())) {
            log.warn("テンプレートバージョン差異: draftId={}, snapshot={}, latest={}",
                    draft.getId(), draft.getTemplateVersionSnapshot(), template.getVersion());
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_006);
        }
        if (template.getEffectiveUntil() != null
                && template.getEffectiveUntil().isBefore(LocalDate.now())) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_006);
        }
    }

    /**
     * テンプレートの form_schema を JSON としてパースし、構造バリデーションを行う。
     *
     * @return パース済みの form_schema JsonNode
     * @throws BusinessException {@link DisclosureErrorCode#DISCLOSURE_004} パース失敗時
     */
    public JsonNode parseAndValidateFormSchema(DisclosureFormTemplateEntity template) {
        JsonNode formSchema = parseJsonOrThrow(template.getFormSchema());
        templateValidator.validate(formSchema);
        return formSchema;
    }

    /**
     * ドラフトの form_data を JSON としてパースする（パース失敗時は空オブジェクトを返す）。
     */
    public JsonNode parseFormData(DisclosureFormDraftEntity draft) {
        return parseJsonOrEmpty(draft.getFormData());
    }

    /**
     * form_schema を走査し、{@code required: true} のフィールド未入力があれば
     * {@link DisclosureErrorCode#DISCLOSURE_007} を投げる。
     */
    public void verifyRequiredFields(JsonNode formSchema, JsonNode formData) {
        List<ErrorResponse.FieldError> missing = new ArrayList<>();
        JsonNode sections = formSchema.get("sections");
        if (sections == null || !sections.isArray()) {
            return;
        }
        for (JsonNode section : sections) {
            JsonNode fields = section.get("fields");
            if (fields == null || !fields.isArray()) {
                continue;
            }
            for (JsonNode field : fields) {
                JsonNode requiredNode = field.get("required");
                if (requiredNode == null || !requiredNode.asBoolean(false)) {
                    continue;
                }
                JsonNode idNode = field.get("id");
                if (idNode == null || !idNode.isTextual()) {
                    continue;
                }
                String fieldId = idNode.asText();
                JsonNode value = formData.get(fieldId);
                if (isEmptyValue(value)) {
                    String label = field.get("label") != null
                            ? field.get("label").asText() : fieldId;
                    missing.add(new ErrorResponse.FieldError(fieldId,
                            "必須項目「" + label + "」が未入力です"));
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_007, missing);
        }
    }

    private boolean isEmptyValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return true;
        }
        if (node.isTextual()) {
            return node.asText().isBlank();
        }
        if (node.isArray()) {
            return node.size() == 0;
        }
        if (node.isObject()) {
            return node.size() == 0;
        }
        return false;
    }

    /**
     * formData の AUTO_TABLE フィールドのうち {@code property_history.packages} を引用するものを走査し、
     * 引用された package id 群について、論理削除済 / is_disclosable=false / クロステナント遮断を
     * 警告として除外する。
     *
     * <p>入力 formData は autoFill 済みの状態と仮定する。除外件数が大きい場合は呼び出し側で
     * UI 警告を表示する想定。本フェーズではログ + warnings 配列で返却する。</p>
     */
    public ReferenceCheckResult verifyPackageReferences(JsonNode formSchema, JsonNode formData,
                                                        Long scopeId) {
        List<Long> includedIds = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        JsonNode sections = formSchema.get("sections");
        if (sections == null || !sections.isArray()) {
            return new ReferenceCheckResult(List.of(), List.of());
        }
        for (JsonNode section : sections) {
            JsonNode fields = section.get("fields");
            if (fields == null || !fields.isArray()) {
                continue;
            }
            for (JsonNode field : fields) {
                JsonNode autoFillFrom = field.get("autoFillFrom");
                if (autoFillFrom == null
                        || !"property_history.packages".equals(autoFillFrom.asText(""))) {
                    continue;
                }
                String fieldId = field.get("id") != null ? field.get("id").asText() : null;
                if (fieldId == null) {
                    continue;
                }
                JsonNode rows = formData.get(fieldId);
                if (rows == null || !rows.isArray()) {
                    continue;
                }
                for (JsonNode row : rows) {
                    JsonNode idNode = row.get("id");
                    if (idNode == null || !idNode.canConvertToLong()) {
                        continue;
                    }
                    long pkgId = idNode.asLong();
                    var entityOpt = propertyWorkPackageRepository.findByIdAndDeletedAtIsNull(pkgId);
                    if (entityOpt.isEmpty()) {
                        warnings.add("パッケージ id=" + pkgId + " は削除済のため除外しました");
                        continue;
                    }
                    PropertyWorkPackageEntity entity = entityOpt.get();
                    if (Boolean.FALSE.equals(entity.getIsDisclosable())) {
                        warnings.add("「" + entity.getTitle() + "」は非開示設定のため除外しました");
                        continue;
                    }
                    if (!scopeId.equals(entity.getScopeId())) {
                        // クロステナント遮断（autoFill では発生しないはずだが防衛的）
                        warnings.add("パッケージ id=" + pkgId + " はスコープ外のため除外しました");
                        continue;
                    }
                    includedIds.add(pkgId);
                }
            }
        }
        return new ReferenceCheckResult(includedIds, warnings);
    }

    private JsonNode parseJsonOrThrow(String json) {
        if (json == null || json.isBlank()) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004);
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004, e);
        }
    }

    private JsonNode parseJsonOrEmpty(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            return objectMapper.createObjectNode();
        }
    }

    /** PropertyWorkPackage 引用整合性検証の結果。 */
    public record ReferenceCheckResult(List<Long> includedIds, List<String> warnings) {
    }
}
