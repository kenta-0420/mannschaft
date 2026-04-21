package com.mannschaft.app.visibility.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.visibility.VisibilityTemplateErrorCode;
import com.mannschaft.app.visibility.dto.CreateVisibilityTemplateRequest;
import com.mannschaft.app.visibility.dto.RuleResponse;
import com.mannschaft.app.visibility.dto.UpdateVisibilityTemplateRequest;
import com.mannschaft.app.visibility.dto.VisibilityTemplateDetailResponse;
import com.mannschaft.app.visibility.dto.VisibilityTemplateListResponse;
import com.mannschaft.app.visibility.dto.VisibilityTemplateSummaryResponse;
import com.mannschaft.app.visibility.entity.VisibilityTemplateEntity;
import com.mannschaft.app.visibility.entity.VisibilityTemplateRuleEntity;
import com.mannschaft.app.visibility.repository.VisibilityTemplateRepository;
import com.mannschaft.app.visibility.repository.VisibilityTemplateRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.IntStream;

/**
 * F01.7 カスタム公開範囲テンプレートサービス。
 *
 * <p>テンプレートの CRUD 操作を提供する。以下のルールに従う:</p>
 * <ul>
 *   <li>カスタムテンプレートは1ユーザーあたり最大10件まで</li>
 *   <li>同一ユーザー内でのテンプレート名重複を禁止する</li>
 *   <li>システムプリセットの変更・削除を禁止する</li>
 *   <li>他ユーザーのテンプレートへのアクセスは404（IDOR対策）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VisibilityTemplateService {

    private final VisibilityTemplateRepository visibilityTemplateRepository;
    private final VisibilityTemplateRuleRepository visibilityTemplateRuleRepository;
    private final VisibilityTemplateEvaluator visibilityTemplateEvaluator;

    /**
     * 自分のテンプレート一覧とシステムプリセット一覧を返す。
     *
     * @param userId リクエストユーザーID
     * @return テンプレート一覧レスポンス（ユーザーテンプレート + プリセット）
     */
    public VisibilityTemplateListResponse listTemplates(Long userId) {
        List<VisibilityTemplateEntity> userTemplates =
                visibilityTemplateRepository.findByOwnerUserIdOrderByCreatedAtDesc(userId);
        List<VisibilityTemplateEntity> systemPresets =
                visibilityTemplateRepository.findByIsSystemPresetTrueOrderByIdAsc();

        return VisibilityTemplateListResponse.builder()
                .userTemplates(userTemplates.stream()
                        .map(t -> toSummaryResponse(t,
                                visibilityTemplateRuleRepository.countByTemplateId(t.getId())))
                        .toList())
                .systemPresets(systemPresets.stream()
                        .map(t -> toSummaryResponse(t,
                                visibilityTemplateRuleRepository.countByTemplateId(t.getId())))
                        .toList())
                .build();
    }

    /**
     * テンプレート詳細（ルール含む）を返す。アクセス権なしは 404 例外。
     *
     * @param templateId テンプレートID
     * @param userId     リクエストユーザーID
     * @return テンプレート詳細レスポンス
     */
    public VisibilityTemplateDetailResponse getTemplate(Long templateId, Long userId) {
        VisibilityTemplateEntity template = visibilityTemplateRepository
                .findAccessibleById(templateId, userId)
                .orElseThrow(() -> new BusinessException(VisibilityTemplateErrorCode.TEMPLATE_NOT_FOUND));

        List<VisibilityTemplateRuleEntity> rules =
                visibilityTemplateRuleRepository.findByTemplateIdOrderBySortOrderAsc(templateId);

        return toDetailResponse(template, rules);
    }

    /**
     * テンプレートを新規作成する。
     *
     * <p>以下の検証を行う:</p>
     * <ul>
     *   <li>カスタムテンプレート数が上限（10件）以下であること</li>
     *   <li>同一ユーザー内でテンプレート名が重複していないこと</li>
     * </ul>
     *
     * @param request テンプレート作成リクエスト
     * @param userId  リクエストユーザーID
     * @return 作成されたテンプレートの詳細レスポンス
     */
    @Transactional
    public VisibilityTemplateDetailResponse createTemplate(CreateVisibilityTemplateRequest request, Long userId) {
        // 上限チェック（10件）
        long count = visibilityTemplateRepository.countByOwnerUserIdAndIsSystemPresetFalse(userId);
        if (count >= 10) {
            throw new BusinessException(VisibilityTemplateErrorCode.TEMPLATE_LIMIT_EXCEEDED);
        }

        // 名前の重複チェック
        if (visibilityTemplateRepository.existsByOwnerUserIdAndName(userId, request.getName())) {
            throw new BusinessException(VisibilityTemplateErrorCode.TEMPLATE_NAME_CONFLICT);
        }

        // テンプレートを保存
        VisibilityTemplateEntity template = VisibilityTemplateEntity.builder()
                .ownerUserId(userId)
                .name(request.getName())
                .description(request.getDescription())
                .iconEmoji(request.getIconEmoji())
                .isSystemPreset(false)
                .build();
        template = visibilityTemplateRepository.save(template);

        // ルールを一括保存（sortOrder を IntStream で付与）
        final VisibilityTemplateEntity savedTemplate = template;
        List<VisibilityTemplateRuleEntity> rules = IntStream.range(0, request.getRules().size())
                .mapToObj(i -> {
                    var ruleReq = request.getRules().get(i);
                    return VisibilityTemplateRuleEntity.builder()
                            .template(savedTemplate)
                            .ruleType(ruleReq.getRuleType())
                            .ruleTargetId(ruleReq.getRuleTargetId())
                            .ruleTargetText(ruleReq.getRuleTargetText())
                            .sortOrder(i)
                            .build();
                })
                .toList();
        rules = visibilityTemplateRuleRepository.saveAll(rules);

        log.debug("テンプレート作成: templateId={}, userId={}, ruleCount={}",
                savedTemplate.getId(), userId, rules.size());

        return toDetailResponse(savedTemplate, rules);
    }

    /**
     * テンプレートを更新する。システムプリセットは 403 例外。
     *
     * <p>以下の検証を行う:</p>
     * <ul>
     *   <li>オーナー確認（IDOR対策で不一致は 404）</li>
     *   <li>システムプリセットでないこと</li>
     *   <li>名前変更時の重複チェック</li>
     * </ul>
     * <p>ルールは削除→再挿入で更新する。</p>
     *
     * @param templateId テンプレートID
     * @param request    テンプレート更新リクエスト
     * @param userId     リクエストユーザーID
     * @return 更新後のテンプレート詳細レスポンス
     */
    @Transactional
    public VisibilityTemplateDetailResponse updateTemplate(
            Long templateId, UpdateVisibilityTemplateRequest request, Long userId) {

        // オーナー確認（IDOR対策: 他人のテンプレートは 404）
        VisibilityTemplateEntity template = visibilityTemplateRepository
                .findByIdAndOwnerUserId(templateId, userId)
                .orElseThrow(() -> new BusinessException(VisibilityTemplateErrorCode.TEMPLATE_NOT_FOUND));

        // プリセット変更禁止
        if (template.isSystemPreset()) {
            throw new BusinessException(VisibilityTemplateErrorCode.FORBIDDEN_PRESET_MODIFY);
        }

        // 名前変更時の重複チェック（同じ名前への変更は許容）
        if (!template.getName().equals(request.getName())
                && visibilityTemplateRepository.existsByOwnerUserIdAndName(userId, request.getName())) {
            throw new BusinessException(VisibilityTemplateErrorCode.TEMPLATE_NAME_CONFLICT);
        }

        // テンプレートを更新（rebuild パターン）
        VisibilityTemplateEntity updatedTemplate = VisibilityTemplateEntity.builder()
                .id(template.getId())
                .ownerUserId(template.getOwnerUserId())
                .name(request.getName())
                .description(request.getDescription())
                .iconEmoji(request.getIconEmoji())
                .isSystemPreset(false)
                .presetKey(template.getPresetKey())
                .build();
        updatedTemplate = visibilityTemplateRepository.save(updatedTemplate);

        // ルールは削除→再挿入で更新
        visibilityTemplateRuleRepository.deleteByTemplateId(templateId);
        final VisibilityTemplateEntity finalTemplate = updatedTemplate;
        List<VisibilityTemplateRuleEntity> newRules = IntStream.range(0, request.getRules().size())
                .mapToObj(i -> {
                    var ruleReq = request.getRules().get(i);
                    return VisibilityTemplateRuleEntity.builder()
                            .template(finalTemplate)
                            .ruleType(ruleReq.getRuleType())
                            .ruleTargetId(ruleReq.getRuleTargetId())
                            .ruleTargetText(ruleReq.getRuleTargetText())
                            .sortOrder(i)
                            .build();
                })
                .toList();
        newRules = visibilityTemplateRuleRepository.saveAll(newRules);

        // キャッシュを無効化
        visibilityTemplateEvaluator.evictTemplateCache(templateId);

        log.debug("テンプレート更新: templateId={}, userId={}, ruleCount={}",
                templateId, userId, newRules.size());

        return toDetailResponse(finalTemplate, newRules);
    }

    /**
     * テンプレートを削除する。システムプリセットは 403 例外。
     *
     * @param templateId テンプレートID
     * @param userId     リクエストユーザーID
     */
    @Transactional
    public void deleteTemplate(Long templateId, Long userId) {
        // オーナー確認（IDOR対策: 他人のテンプレートは 404）
        VisibilityTemplateEntity template = visibilityTemplateRepository
                .findByIdAndOwnerUserId(templateId, userId)
                .orElseThrow(() -> new BusinessException(VisibilityTemplateErrorCode.TEMPLATE_NOT_FOUND));

        // プリセット削除禁止
        if (template.isSystemPreset()) {
            throw new BusinessException(VisibilityTemplateErrorCode.FORBIDDEN_PRESET_MODIFY);
        }

        // 削除（cascade により rules も削除される）
        visibilityTemplateRepository.deleteById(templateId);

        // キャッシュを無効化
        visibilityTemplateEvaluator.evictTemplateCache(templateId);

        log.debug("テンプレート削除: templateId={}, userId={}", templateId, userId);
    }

    // ============================
    // private ヘルパーメソッド
    // ============================

    /**
     * Entity → VisibilityTemplateSummaryResponse に変換する。
     */
    private VisibilityTemplateSummaryResponse toSummaryResponse(
            VisibilityTemplateEntity template, long ruleCount) {
        return VisibilityTemplateSummaryResponse.builder()
                .id(template.getId())
                .name(template.getName())
                .description(template.getDescription())
                .iconEmoji(template.getIconEmoji())
                .isSystemPreset(template.isSystemPreset())
                .presetKey(template.getPresetKey())
                .ruleCount(ruleCount)
                .createdAt(template.getCreatedAt())
                .updatedAt(template.getUpdatedAt())
                .build();
    }

    /**
     * Entity + ルール一覧 → VisibilityTemplateDetailResponse に変換する。
     */
    private VisibilityTemplateDetailResponse toDetailResponse(
            VisibilityTemplateEntity template, List<VisibilityTemplateRuleEntity> rules) {
        return VisibilityTemplateDetailResponse.builder()
                .id(template.getId())
                .name(template.getName())
                .description(template.getDescription())
                .iconEmoji(template.getIconEmoji())
                .isSystemPreset(template.isSystemPreset())
                .rules(rules.stream()
                        .map(r -> RuleResponse.builder()
                                .id(r.getId())
                                .ruleType(r.getRuleType())
                                .ruleTargetId(r.getRuleTargetId())
                                .ruleTargetText(r.getRuleTargetText())
                                .sortOrder(r.getSortOrder())
                                .build())
                        .toList())
                .createdAt(template.getCreatedAt())
                .updatedAt(template.getUpdatedAt())
                .build();
    }
}
