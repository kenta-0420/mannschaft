package com.mannschaft.app.publicview.service;

import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.publicview.dto.PublicOrganizationSearchResultResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * F19.1 Phase 4 公開組織検索クエリサービス。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §7.x Phase 4</p>
 *
 * <p>{@code GET /api/v1/public/organizations/search} の検索ロジックを担う。
 * 認証不要の横断検索のため、テナント絞り込み（{@code AbstractTenantAwareRepository}）は
 * 適用しない（CLAUDE.md アーキテクチャ原則 7 の「公開横断検索」例外）。</p>
 */
// TODO: publicviewドメインからorganizationドメイン(OrganizationRepository)とcmsドメイン(BlogPostRepository)を
//       横断参照。将来はOrganizationSearchedEvent/PostIndexedEventで分離予定
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class PublicOrganizationSearchQueryService {

    private final OrganizationRepository organizationRepository;
    private final BlogPostRepository blogPostRepository;
    private final MediaUrlResolver mediaUrlResolver;

    /**
     * 公開組織を keyword / prefecture で検索する。
     *
     * <p>N+1 を防ぐため、組織ページを取得後に lastPostDate を 1 本のクエリで一括取得する。</p>
     *
     * @param keyword    組織名・読み仮名の部分一致キーワード（null または空文字で全件対象）
     * @param prefecture 都道府県名の完全一致（null または空文字で絞り込みなし）
     * @param pageable   ページング情報
     * @return PUBLIC 組織の検索結果ページ
     */
    public Page<PublicOrganizationSearchResultResponse> search(
            String keyword, String prefecture, Pageable pageable) {

        // null や空文字は null として扱い、クエリ側で「絞り込みなし」として処理する
        String effectiveKeyword = StringUtils.hasText(keyword) ? keyword : null;
        String effectivePrefecture = StringUtils.hasText(prefecture) ? prefecture : null;

        Page<OrganizationEntity> orgPage = organizationRepository.searchPublicOrganizations(
                effectiveKeyword, effectivePrefecture, pageable);

        if (orgPage.isEmpty()) {
            return Page.empty(pageable);
        }

        // N+1 を防ぐために lastPostDate を一括取得してマップに変換する
        Set<Long> orgIds = orgPage.getContent().stream()
                .map(OrganizationEntity::getId)
                .collect(Collectors.toSet());
        Map<Long, LocalDateTime> lastPostDateMap = buildLastPostDateMap(orgIds);

        List<PublicOrganizationSearchResultResponse> content = orgPage.getContent().stream()
                .map(org -> new PublicOrganizationSearchResultResponse(
                        org.getId(),
                        org.getSlug(),
                        org.getName(),
                        // 画像 URL 根治 Phase 2: 生 R2 キーを署名付き表示 URL へ解決
                        mediaUrlResolver.resolve(org.getIconUrl()),
                        0, // 組織はmember_count集計カラムを持たないため、メンバー数は0として返す
                        lastPostDateMap.get(org.getId())
                ))
                .toList();

        return new PageImpl<>(content, pageable, orgPage.getTotalElements());
    }

    /**
     * 組織 ID 集合に対する最新投稿日時マップを構築する。
     *
     * @param orgIds 組織 ID 集合
     * @return organizationId → lastPostDate のマップ（投稿なしの組織はエントリなし）
     */
    private Map<Long, LocalDateTime> buildLastPostDateMap(Set<Long> orgIds) {
        List<Object[]> rows = blogPostRepository.findMaxCreatedAtByOrganizationIdIn(orgIds);
        return rows.stream()
                .filter(row -> row[0] != null && row[1] != null)
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (LocalDateTime) row[1]
                ));
    }
}
