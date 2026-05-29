package com.mannschaft.app.faq.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.faq.FaqCategory;
import com.mannschaft.app.faq.FixedFaqQuestion;
import com.mannschaft.app.faq.ScopeType;
import com.mannschaft.app.faq.dto.PublicFaqResponse;
import com.mannschaft.app.faq.entity.FaqEntity;
import com.mannschaft.app.faq.repository.FaqRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.publicview.error.PublicViewErrorCode;
import com.mannschaft.app.publicview.service.PublicTeamQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * F21.1 §5.5.6: 公開FAQ取得クエリサービス。
 *
 * <p>未認証で叩かれる {@code GET /api/v1/public/{teams|organizations}/{id}/faqs} のロジックを担う。</p>
 *
 * <p><strong>IDOR / エニュメレーション対策</strong>: 対象チーム / 組織が PUBLIC かつ
 * 未 archive かつ未削除であることを必ず先に確認し、条件を満たさない（PRIVATE / archived /
 * 削除済 / 不在）場合は一律 {@link PublicViewErrorCode#PUBLIC_001}（404 へ正規化）を返す。
 * 状態を区別しないことで「ID が存在するか」「PRIVATE か」を推測されないようにする。
 * 公開可否判定は F19.1 の既存流儀をそのまま踏襲する:
 * <ul>
 *   <li>チーム: {@link PublicTeamQueryService#requirePublicTeam(Long)}
 *       （内部で {@code TeamRepository#findPublicTeamById} を用い PUBLIC + active のみ返す）</li>
 *   <li>組織: {@link OrganizationRepository#findPublicOrganizationById(Long)}
 *       （PUBLIC + 未 archive + 未削除のみ返す）</li>
 * </ul>
 * </p>
 *
 * <p>取得後は<strong>回答済み（{@code answerText} 非空・{@code deletedAt} が NULL）</strong>の
 * FAQ のみを残し、「固定質問（{@code questionKey} 非NULL）を
 * {@link FixedFaqQuestion#displayOrder()} 昇順 → 自由質問（{@code questionKey} NULL）を
 * {@code displayOrder} 昇順」で並べて DTO 化する。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class PublicFaqQueryService {

    private final FaqRepository faqRepository;
    private final PublicTeamQueryService publicTeamQueryService;
    private final OrganizationRepository organizationRepository;
    private final FaqCategoryResolver faqCategoryResolver;

    /**
     * 公開チームのFAQを取得する。
     *
     * @param teamId チーム ID
     * @return 回答済みFAQの公開レスポンス（固定→自由順）
     * @throws BusinessException PRIVATE / archived / 削除済 / 不在の場合（{@link PublicViewErrorCode#PUBLIC_001}）
     */
    public List<PublicFaqResponse> getPublicTeamFaqs(Long teamId) {
        // 公開可否判定（PRIVATE / archived / 削除済 / 不在は 404 へ正規化）。
        var team = publicTeamQueryService.requirePublicTeam(teamId);
        FaqCategory category = faqCategoryResolver.resolve(team.getTemplate());
        return toResponses(ScopeType.TEAM, teamId, category);
    }

    /**
     * 公開組織のFAQを取得する。
     *
     * @param orgId 組織 ID
     * @return 回答済みFAQの公開レスポンス（固定→自由順）
     * @throws BusinessException PRIVATE / archived / 削除済 / 不在の場合（{@link PublicViewErrorCode#PUBLIC_001}）
     */
    public List<PublicFaqResponse> getPublicOrganizationFaqs(Long orgId) {
        // 公開可否判定（PRIVATE / archived / 削除済 / 不在は 404 へ正規化）。
        OrganizationEntity org = organizationRepository.findPublicOrganizationById(orgId)
                .orElseThrow(() -> new BusinessException(PublicViewErrorCode.PUBLIC_001));
        FaqCategory category = faqCategoryResolver.resolve(
                org.getOrgType() != null ? org.getOrgType().name() : null);
        return toResponses(ScopeType.ORGANIZATION, orgId, category);
    }

    /**
     * 指定スコープの有効FAQから回答済みのみを抽出・整列し DTO 化する。
     *
     * <p>固定質問は<strong>現カテゴリに属するキーのみ</strong>を含める。団体がカテゴリを変更した
     * 場合に残る旧カテゴリの question_key は公開出力に含めない（現カテゴリの固定質問 + 自由質問のみ）。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープ ID（チーム / 組織 ID）
     * @param category  対象団体の現FAQカテゴリ
     * @return 公開レスポンスリスト（固定質問 displayOrder 昇順 → 自由質問 displayOrder 昇順）
     */
    private List<PublicFaqResponse> toResponses(ScopeType scopeType, Long scopeId, FaqCategory category) {
        // 現カテゴリに属する固定質問キーの集合
        Set<String> categoryKeys = new HashSet<>();
        for (FixedFaqQuestion q : FixedFaqQuestion.ofCategory(category)) {
            categoryKeys.add(q.name());
        }

        List<FaqEntity> faqs = faqRepository
                .findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(scopeType, scopeId);

        return faqs.stream()
                // 回答済みのみ（answer_text が非空）。deleted_at が NULL なのはクエリで担保済み。
                .filter(f -> StringUtils.hasText(f.getAnswerText()))
                // 固定質問は現カテゴリに属するキーのみ残す（カテゴリ変更後の旧キー残骸を除外）。
                // 自由質問（questionKey NULL）は常に残す。
                .filter(f -> f.getQuestionKey() == null || categoryKeys.contains(f.getQuestionKey()))
                // 固定質問（questionKey 非NULL）を先に、その中は FixedFaqQuestion.displayOrder 昇順。
                // 自由質問（questionKey NULL）を後に、その中は entity.displayOrder 昇順。
                .sorted(Comparator
                        .comparingInt((FaqEntity f) -> f.getQuestionKey() != null ? 0 : 1)
                        .thenComparingInt(PublicFaqQueryService::orderWithin))
                .map(PublicFaqQueryService::toResponse)
                .toList();
    }

    /**
     * 同グループ内の整列キーを返す。
     *
     * <p>固定質問は {@link FixedFaqQuestion#displayOrder()}（不正な key の場合は entity.displayOrder に
     * フォールバック）、自由質問は entity.displayOrder を用いる。</p>
     */
    private static int orderWithin(FaqEntity f) {
        if (f.getQuestionKey() != null) {
            return FixedFaqQuestion.fromKey(f.getQuestionKey())
                    .map(FixedFaqQuestion::displayOrder)
                    .orElseGet(f::getDisplayOrder);
        }
        return f.getDisplayOrder();
    }

    /**
     * FAQ Entity を公開レスポンスへ変換する。
     *
     * <p>固定質問は {@code questionKey} を返し {@code questionText} は {@code null}（FE が i18n 描画）。
     * 自由質問は {@code questionKey} を {@code null} とし {@code questionText} に保存値を返す。</p>
     */
    private static PublicFaqResponse toResponse(FaqEntity f) {
        return new PublicFaqResponse(
                f.getQuestionKey(),
                f.getQuestionText(),
                f.getAnswerText());
    }
}
