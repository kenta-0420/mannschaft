package com.mannschaft.app.faq.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.faq.FaqCategory;
import com.mannschaft.app.faq.FixedFaqQuestion;
import com.mannschaft.app.faq.ScopeType;
import com.mannschaft.app.faq.dto.FaqEditorResponse;
import com.mannschaft.app.faq.dto.SaveFaqRequest;
import com.mannschaft.app.faq.entity.FaqEntity;
import com.mannschaft.app.faq.error.FaqErrorCode;
import com.mannschaft.app.faq.repository.FaqRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * F21.1 §5.5: FAQ 管理サービス（取得・一括 upsert）。
 *
 * <p>固定質問は (scope, questionKey) で UPSERT、自由質問は差分適用する。
 * 論理削除（deletedAt）を用い、物理削除はしない。</p>
 *
 * <p><strong>権限チェック（per-scope 認可の真の強制点）:</strong>
 * JWT には {@code MEMBER} しか乗らず、ADMIN/DEPUTY_ADMIN は {@code user_roles} にスコープ別保持されるため
 * {@code hasRole} では per-scope（その team / org の管理者か）の判定にならない。</p>
 *
 * <p>そこで本サービスの {@code getEditorPayload} / {@code save} の双方で、処理本体の前に
 * {@link AccessControlService#checkAdminOrAbove(Long, Long, String)} による per-scope 認可を
 * 実施し、**他団体の FAQ 編集・閲覧を遮断する**。SYSTEM_ADMIN は全スコープ許可。
 * 認可の置き場所を Service 層に集中させているのは、4 つの Controller 入口
 * （team/org × GET/PUT）すべてが本サービスの 2 メソッドへ収束するため、ここ 1 箇所で
 * 確実かつ重複なく効かせられるからである（手本の TeamBudgetConfigController /
 * OrganizationAdMessagingCampaignTransitionController は Controller 層に置くが、FAQ は
 * 入口が複数・サービスが choke point となる構造ゆえ Service 集中が馴染む）。</p>
 *
 * <p>本サービスでは加えて対象スコープ（チーム / 組織）の存在確認も実施する（IDOR 対策）。</p>
 *
 * <p><strong>クロスドメイン参照:</strong> faq → team / organization の存在確認は
 * クロスドメイン参照（CLAUDE.md 原則5）。読み取りのみで faq ドメイン内の
 * {@code @Transactional} に閉じ、将来はイベント駆動化候補として記録する。</p>
 *
 * <p>設計書: docs/features/F21.1_geo_optimization.md §5.5</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaqAdminService {

    private final FaqRepository faqRepository;
    // TODO: faq → team のクロスドメイン参照（存在確認・カテゴリ解決のみ）。将来はイベント駆動化候補。
    private final TeamRepository teamRepository;
    // TODO: faq → organization のクロスドメイン参照（存在確認・カテゴリ解決のみ）。将来はイベント駆動化候補。
    private final OrganizationRepository organizationRepository;
    private final FaqCategoryResolver faqCategoryResolver;
    private final AccessControlService accessControlService;

    /**
     * 指定スコープの FAQ 編集画面用ペイロードを取得する。
     *
     * <p>固定6問は未回答含め全件（{@link FixedFaqQuestion} の displayOrder 昇順）を返す。
     * 自由質問は display_order 昇順で返す。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   チーム / 組織 ID
     * @return 編集画面用ペイロード
     * @throws BusinessException 対象スコープが存在しない（FAQ_010、404）
     */
    @Transactional(readOnly = true)
    public FaqEditorResponse getEditorPayload(ScopeType scopeType, Long scopeId) {
        // per-scope 認可（編集画面の取得も管理者限定）。SYSTEM_ADMIN は全スコープ許可。
        checkScopeAdminAccess(scopeType, scopeId);
        FaqCategory category = resolveCategory(scopeType, scopeId);

        List<FaqEntity> existing =
                faqRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(scopeType, scopeId);

        // 固定質問の既存回答を questionKey でインデックス化
        Map<String, FaqEntity> fixedByKey = new LinkedHashMap<>();
        List<FaqEntity> customs = new ArrayList<>();
        for (FaqEntity e : existing) {
            if (e.getQuestionKey() != null) {
                fixedByKey.put(e.getQuestionKey(), e);
            } else {
                customs.add(e);
            }
        }

        // 解決カテゴリの固定6問のみを displayOrder 昇順で全件返す（未回答は answer=null）。
        // カテゴリ外の question_key（団体がカテゴリ変更した場合の残骸）は編集画面に出さない。
        List<FaqEditorResponse.FixedFaqItem> fixedItems = new ArrayList<>();
        for (FixedFaqQuestion q : FixedFaqQuestion.ofCategory(category)) {
            FaqEntity e = fixedByKey.get(q.name());
            fixedItems.add(FaqEditorResponse.FixedFaqItem.builder()
                    .questionKey(q.name())
                    .displayOrder(q.displayOrder())
                    .answer(e != null ? e.getAnswerText() : null)
                    .build());
        }

        // 自由質問は display_order 昇順（リポジトリで既にソート済）
        List<FaqEditorResponse.CustomFaqItem> customItems = new ArrayList<>();
        for (FaqEntity e : customs) {
            customItems.add(FaqEditorResponse.CustomFaqItem.builder()
                    .id(e.getId().toString())
                    .questionText(e.getQuestionText())
                    .answer(e.getAnswerText())
                    .displayOrder(e.getDisplayOrder())
                    .build());
        }

        return FaqEditorResponse.builder()
                .category(category.name())
                .fixedQuestions(fixedItems)
                .customFaqs(customItems)
                .build();
    }

    /**
     * 指定スコープの FAQ を一括 upsert する。
     *
     * <p>固定質問: answer 非空なら UPSERT（既存無ければ create、createdBy=操作ユーザー）、
     * answer 空なら既存を論理削除。
     * 自由質問: リクエストの id に無い既存は論理削除、id 有りは更新、id 無しは新規作成。</p>
     *
     * @param scopeType  スコープ種別
     * @param scopeId    チーム / 組織 ID
     * @param req        一括 upsert リクエスト
     * @param operatorId 操作ユーザー ID（createdBy に設定）
     * @throws BusinessException 対象不在（FAQ_010）/ バリデーション違反（FAQ_001〜005）
     */
    @Transactional
    public void save(ScopeType scopeType, Long scopeId, SaveFaqRequest req, Long operatorId) {
        // per-scope 認可（保存は当該 team / org の管理者のみ）。SYSTEM_ADMIN は全スコープ許可。
        checkScopeAdminAccess(scopeType, scopeId);
        FaqCategory category = resolveCategory(scopeType, scopeId);
        validate(req, category);

        LocalDateTime now = LocalDateTime.now();

        upsertFixed(scopeType, scopeId, req, operatorId);
        upsertCustom(scopeType, scopeId, req, operatorId, now);

        log.info("FAQ 一括保存: scopeType={}, scopeId={}, fixed={}件, custom={}件, operatorId={}",
                scopeType, scopeId,
                req.getFixedAnswers() != null ? req.getFixedAnswers().size() : 0,
                req.getCustomFaqs() != null ? req.getCustomFaqs().size() : 0,
                operatorId);
    }

    /**
     * 固定質問の UPSERT。answer 非空なら作成 / 更新、空なら論理削除。
     */
    private void upsertFixed(ScopeType scopeType, Long scopeId, SaveFaqRequest req, Long operatorId) {
        if (req.getFixedAnswers() == null) {
            return;
        }
        for (SaveFaqRequest.FixedAnswer fa : req.getFixedAnswers()) {
            String key = fa.getQuestionKey();
            FixedFaqQuestion fixed = FixedFaqQuestion.fromKey(key)
                    .orElseThrow(() -> new BusinessException(FaqErrorCode.FAQ_002));

            FaqEntity existing = faqRepository
                    .findByScopeTypeAndScopeIdAndQuestionKeyAndDeletedAtIsNull(scopeType, scopeId, key)
                    .orElse(null);

            boolean hasAnswer = fa.getAnswer() != null && !fa.getAnswer().isBlank();

            if (hasAnswer) {
                if (existing != null) {
                    existing.setAnswerText(fa.getAnswer());
                    existing.setDisplayOrder(fixed.displayOrder());
                    faqRepository.save(existing);
                } else {
                    FaqEntity created = FaqEntity.builder()
                            .scopeType(scopeType)
                            .scopeId(scopeId)
                            .questionKey(key)
                            .questionText(null)
                            .answerText(fa.getAnswer())
                            .displayOrder(fixed.displayOrder())
                            .createdBy(operatorId)
                            .build();
                    faqRepository.save(created);
                }
            } else if (existing != null) {
                // 回答クリア = 論理削除
                existing.setDeletedAt(LocalDateTime.now());
                faqRepository.save(existing);
            }
        }
    }

    /**
     * 自由質問の差分適用。リクエストに無い既存は論理削除、id 有りは更新、id 無しは新規作成。
     */
    private void upsertCustom(ScopeType scopeType, Long scopeId, SaveFaqRequest req,
                              Long operatorId, LocalDateTime now) {
        List<SaveFaqRequest.CustomFaqInput> inputs =
                req.getCustomFaqs() != null ? req.getCustomFaqs() : List.of();

        // 既存の自由質問を取得
        List<FaqEntity> existingCustoms = faqRepository
                .findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(scopeType, scopeId)
                .stream()
                .filter(e -> e.getQuestionKey() == null)
                .toList();

        Map<UUID, FaqEntity> existingById = new LinkedHashMap<>();
        for (FaqEntity e : existingCustoms) {
            existingById.put(e.getId(), e);
        }

        // リクエストに残った id を収集
        Set<UUID> keepIds = new HashSet<>();
        for (SaveFaqRequest.CustomFaqInput in : inputs) {
            if (in.getId() != null) {
                keepIds.add(in.getId());
            }
        }

        // リクエストに無い既存は論理削除
        for (FaqEntity e : existingCustoms) {
            if (!keepIds.contains(e.getId())) {
                e.setDeletedAt(now);
                faqRepository.save(e);
            }
        }

        // 更新 / 新規作成（リクエスト順を表示順の既定とする）
        int index = 0;
        for (SaveFaqRequest.CustomFaqInput in : inputs) {
            int displayOrder = in.getDisplayOrder() != null ? in.getDisplayOrder() : index;
            if (in.getId() != null) {
                FaqEntity e = existingById.get(in.getId());
                if (e == null) {
                    // 指定 id が当該スコープに存在しない（他スコープ流用 / 不正 id）。IDOR 対策で 404 相当。
                    throw new BusinessException(FaqErrorCode.FAQ_010);
                }
                e.setQuestionText(in.getQuestionText());
                e.setAnswerText(in.getAnswer() != null ? in.getAnswer() : "");
                e.setDisplayOrder(displayOrder);
                faqRepository.save(e);
            } else {
                FaqEntity created = FaqEntity.builder()
                        .scopeType(scopeType)
                        .scopeId(scopeId)
                        .questionKey(null)
                        .questionText(in.getQuestionText())
                        .answerText(in.getAnswer() != null ? in.getAnswer() : "")
                        .displayOrder(displayOrder)
                        .createdBy(operatorId)
                        .build();
                faqRepository.save(created);
            }
            index++;
        }
    }

    /**
     * サービス層バリデーション（Bean Validation で表現できない業務制約）。
     *
     * @param req      一括 upsert リクエスト
     * @param category 対象団体のFAQカテゴリ（固定質問キーの所属検証に用いる）
     */
    private void validate(SaveFaqRequest req, FaqCategory category) {
        // 自由質問件数上限（Bean Validation の @Size と二重防御）
        if (req.getCustomFaqs() != null && req.getCustomFaqs().size() > SaveFaqRequest.MAX_CUSTOM_FAQS) {
            throw new BusinessException(FaqErrorCode.FAQ_001);
        }

        // 固定質問: questionKey 妥当性（存在 + 対象団体のカテゴリに属する）+ 重複チェック
        if (req.getFixedAnswers() != null) {
            Set<String> seen = new HashSet<>();
            for (SaveFaqRequest.FixedAnswer fa : req.getFixedAnswers()) {
                String key = fa.getQuestionKey();
                FixedFaqQuestion fixed = FixedFaqQuestion.fromKey(key)
                        .orElseThrow(() -> new BusinessException(FaqErrorCode.FAQ_002));
                // カテゴリ外の固定質問キーは不正キー扱い（FAQ_002）
                if (fixed.category() != category) {
                    throw new BusinessException(FaqErrorCode.FAQ_002);
                }
                if (!seen.add(key)) {
                    throw new BusinessException(FaqErrorCode.FAQ_003);
                }
                if (fa.getAnswer() != null && fa.getAnswer().length() > SaveFaqRequest.MAX_ANSWER_LENGTH) {
                    throw new BusinessException(FaqErrorCode.FAQ_005);
                }
            }
        }

        // 自由質問: 質問文必須 + 長さ
        if (req.getCustomFaqs() != null) {
            for (SaveFaqRequest.CustomFaqInput in : req.getCustomFaqs()) {
                if (in.getQuestionText() == null || in.getQuestionText().isBlank()) {
                    throw new BusinessException(FaqErrorCode.FAQ_004);
                }
                if (in.getQuestionText().length() > SaveFaqRequest.MAX_QUESTION_TEXT_LENGTH) {
                    throw new BusinessException(FaqErrorCode.FAQ_005);
                }
                if (in.getAnswer() != null && in.getAnswer().length() > SaveFaqRequest.MAX_ANSWER_LENGTH) {
                    throw new BusinessException(FaqErrorCode.FAQ_005);
                }
            }
        }
    }

    /**
     * 対象スコープ（チーム / 組織）に対する per-scope 認可を実施する。
     *
     * <p>SYSTEM_ADMIN は全スコープ許可。それ以外は当該スコープの ADMIN / DEPUTY_ADMIN のみ許可し、
     * 違反時は {@link AccessControlService#checkAdminOrAbove} が {@code COMMON_002}（403）を投げる。
     * これにより、別の team / org の管理者が他団体の FAQ を編集・閲覧することを遮断する。</p>
     *
     * <p>{@code AccessControlService} が期待する scopeType 文字列は {@code "TEAM"} / {@code "ORGANIZATION"}
     * であり、{@link ScopeType#name()}（{@code TEAM} / {@code ORGANIZATION}）がそのまま一致する。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   チーム / 組織 ID
     * @throws BusinessException 当該スコープの管理者でない場合（COMMON_002、403）
     */
    private void checkScopeAdminAccess(ScopeType scopeType, Long scopeId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType.name());
    }

    /**
     * 対象スコープ（チーム / 組織）の存在確認とFAQカテゴリ解決を同時に行う。
     *
     * <p>IDOR 対策で不在（削除済含む）は FAQ_010（404）。存在する場合は団体の種別
     * （チーム template / 組織 orgType）から {@link FaqCategoryResolver} でカテゴリを解決して返す。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   チーム / 組織 ID
     * @return 解決された {@link FaqCategory}
     * @throws BusinessException 対象不在（FAQ_010、404）
     */
    private FaqCategory resolveCategory(ScopeType scopeType, Long scopeId) {
        return switch (scopeType) {
            // TODO: faq → team クロスドメイン参照（存在確認・カテゴリ解決のみ）
            case TEAM -> teamRepository.findById(scopeId)
                    .filter(t -> t.getDeletedAt() == null)
                    .map(t -> faqCategoryResolver.resolve(t.getTemplate()))
                    .orElseThrow(() -> new BusinessException(FaqErrorCode.FAQ_010));
            // TODO: faq → organization クロスドメイン参照（存在確認・カテゴリ解決のみ）
            case ORGANIZATION -> organizationRepository.findById(scopeId)
                    .filter(o -> o.getDeletedAt() == null)
                    .map(o -> faqCategoryResolver.resolve(
                            o.getOrgType() != null ? o.getOrgType().name() : null))
                    .orElseThrow(() -> new BusinessException(FaqErrorCode.FAQ_010));
        };
    }
}
