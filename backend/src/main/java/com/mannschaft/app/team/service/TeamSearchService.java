package com.mannschaft.app.team.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.exception.OrganizationNotFoundException;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.dto.TeamSearchCriteria;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;

/**
 * F15.4 組織内チーム（店舗）検索のサービス層。
 *
 * <p>設計書 {@code docs/features/F15.4_team_store_search_within_org.md §4.3} の権限判定フローを実装する。</p>
 *
 * <p>本サービスは未ログイン参照を許容するため {@code currentUserId} に {@code null} を受け取れる。
 * Controller 側でレスポンス DTO の射影（{@code TeamPublicSummaryResponse} / {@code TeamSearchResultResponse}）を行うため、
 * 本サービスは {@link Page}{@code <TeamEntity>} のまま返す。</p>
 */
@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class TeamSearchService {

    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";

    /** ソート許可カラムのホワイトリスト（設計書 §3.2）。 */
    private static final Set<String> ALLOWED_SORT_PROPERTIES = Set.of("nameKana", "name", "createdAt");

    private final OrganizationRepository organizationRepository;
    private final TeamRepository teamRepository;
    private final AccessControlService accessControlService;

    /**
     * 組織配下のチームを検索する。
     *
     * <p>呼び出しフロー（設計書 §4.3）:
     * <ol>
     *   <li>ソートカラムをホワイトリスト検証</li>
     *   <li>組織取得（{@code @SQLRestriction} により論理削除済みは自動除外）</li>
     *   <li>組織が PUBLIC でない場合は組織メンバーのみ続行、それ以外は 404 として隠蔽</li>
     *   <li>許可 {@code visibility} 集合を決定</li>
     *   <li>条件を合成して {@link TeamRepository#findAll(Specification, Pageable)} を実行</li>
     * </ol>
     * </p>
     *
     * @param orgId         組織 ID
     * @param criteria      検索条件（null 不可、各項目は任意）
     * @param currentUserId ログインユーザー ID（未ログインの場合 {@code null}）
     * @param pageable      ページング・ソート指定
     * @return 検索結果（権限スコープに合った可視性のチームのみ）
     * @throws OrganizationNotFoundException 組織が存在しない／論理削除済み／PRIVATE で未ログイン
     * @throws IllegalArgumentException      sort カラムがホワイトリスト外
     */
    public Page<TeamEntity> search(
            Long orgId,
            TeamSearchCriteria criteria,
            Long currentUserId,
            Pageable pageable
    ) {
        validateSort(pageable);

        // 1. 組織の存在確認（@SQLRestriction("deleted_at IS NULL") により論理削除済みは自動除外）
        OrganizationEntity organization = organizationRepository.findById(orgId)
                .orElseThrow(OrganizationNotFoundException::new);

        // 2. PRIVATE 組織の閲覧権限確認（エニュメレーション対策で 404）
        boolean isMember = isOrganizationMember(orgId, currentUserId);
        if (organization.getVisibility() != OrganizationEntity.Visibility.PUBLIC && !isMember) {
            throw new OrganizationNotFoundException();
        }

        // 3. 許可 visibility 集合の決定
        Set<TeamEntity.Visibility> allowedVisibilities = isMember
                ? EnumSet.of(TeamEntity.Visibility.PUBLIC, TeamEntity.Visibility.ORGANIZATION_ONLY)
                : EnumSet.of(TeamEntity.Visibility.PUBLIC);

        // 4. prefecture 未指定 & city 指定のときは city を無視（警告ログのみ、400 にしない）
        String effectiveCity = criteria.city();
        if ((criteria.prefecture() == null || criteria.prefecture().isBlank())
                && effectiveCity != null && !effectiveCity.isBlank()) {
            log.warn("F15.4 team search: prefecture が未指定のため city パラメータを無視します（orgId={}）", orgId);
            effectiveCity = null;
        }

        // 5. Specification 合成
        Specification<TeamEntity> spec = Specification
                .where(TeamSearchSpecifications.notDeleted())
                .and(TeamSearchSpecifications.notArchived())
                .and(TeamSearchSpecifications.belongsToOrganization(orgId))
                .and(TeamSearchSpecifications.visibilityIn(allowedVisibilities))
                .and(TeamSearchSpecifications.nameOrKanaContains(criteria.keyword()))
                .and(TeamSearchSpecifications.prefectureEquals(criteria.prefecture()))
                .and(TeamSearchSpecifications.cityEquals(effectiveCity))
                .and(TeamSearchSpecifications.templateEquals(criteria.template()));

        return teamRepository.findAll(spec, pageable);
    }

    /**
     * 当該ユーザーが組織のメンバーかどうかを返す。
     *
     * <p>{@code currentUserId} が {@code null} の場合は常に {@code false}（未ログインは非メンバー扱い）。</p>
     *
     * @param orgId         組織 ID
     * @param currentUserId ログインユーザー ID（未ログインの場合 {@code null}）
     * @return 組織メンバーであれば {@code true}
     */
    public boolean isOrganizationMember(Long orgId, Long currentUserId) {
        if (currentUserId == null) {
            return false;
        }
        return accessControlService.isMember(currentUserId, orgId, SCOPE_ORGANIZATION);
    }

    /**
     * ソートカラムがホワイトリストに含まれているかを検証する。
     *
     * @throws IllegalArgumentException 許可外のカラムが指定された場合
     */
    private void validateSort(Pageable pageable) {
        if (pageable == null || pageable.getSort().isUnsorted()) {
            return;
        }
        for (Sort.Order order : pageable.getSort()) {
            if (!ALLOWED_SORT_PROPERTIES.contains(order.getProperty())) {
                throw new IllegalArgumentException(
                        "Invalid sort property: " + order.getProperty()
                                + " (allowed: " + ALLOWED_SORT_PROPERTIES + ")"
                );
            }
        }
    }
}
