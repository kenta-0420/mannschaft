package com.mannschaft.app.publicview.service;

import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.publicview.dto.PublicTeamSearchResultResponse;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
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
 * F19.1 Phase 4 公開チーム検索クエリサービス。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §7.x Phase 4</p>
 *
 * <p>{@code GET /api/v1/public/teams/search} の検索ロジックを担う。
 * 認証不要の横断検索のため、テナント絞り込み（{@code AbstractTenantAwareRepository}）は
 * 適用しない（CLAUDE.md アーキテクチャ原則 7 の「公開横断検索」例外）。</p>
 */
// TODO: publicviewドメインからteamドメイン(TeamRepository)とcmsドメイン(BlogPostRepository)を
//       横断参照。将来はTeamSearchedEvent/PostIndexedEventで分離予定
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class PublicTeamSearchQueryService {

    private final TeamRepository teamRepository;
    private final BlogPostRepository blogPostRepository;

    /**
     * 公開チームを keyword / prefecture（名称）で検索する（後方互換オーバーロード）。
     *
     * <p>地域コードによる絞り込みを使わない既存呼び出し向け。
     * 内部で {@code prefectureCode=null} として {@link #search(String, String, String, Pageable)} に委譲する。</p>
     *
     * @param keyword    チーム名・読み仮名の部分一致キーワード（null または空文字で全件対象）
     * @param prefecture 都道府県名の完全一致（null または空文字で絞り込みなし）
     * @param pageable   ページング情報
     * @return PUBLIC チームの検索結果ページ
     */
    public Page<PublicTeamSearchResultResponse> search(
            String keyword, String prefecture, Pageable pageable) {
        return search(keyword, prefecture, null, pageable);
    }

    /**
     * 公開チームを keyword / prefecture（名称）/ prefectureCode（コード）で検索する。
     *
     * <p>N+1 を防ぐため、チームページを取得後に lastPostDate を 1 本のクエリで一括取得する。</p>
     *
     * <p>F22.1 市 Phase 2 足場C: 地域フィルタは <strong>dual-support</strong>。
     * {@code prefectureCode} 指定時は構造化キー優先、未指定なら名称 {@code prefecture} にフォールバックする。</p>
     *
     * @param keyword        チーム名・読み仮名の部分一致キーワード（null または空文字で全件対象）
     * @param prefecture     都道府県名の完全一致（{@code prefectureCode} 未指定時のフォールバック。null/空で絞り込みなし）
     * @param prefectureCode 都道府県コードの完全一致（指定時は名称より優先。null/空で名称フォールバック）
     * @param pageable       ページング情報
     * @return PUBLIC チームの検索結果ページ
     */
    public Page<PublicTeamSearchResultResponse> search(
            String keyword, String prefecture, String prefectureCode, Pageable pageable) {

        // null や空文字は null として扱い、クエリ側で「絞り込みなし」として処理する
        String effectiveKeyword = StringUtils.hasText(keyword) ? keyword : null;
        String effectivePrefecture = StringUtils.hasText(prefecture) ? prefecture : null;
        String effectivePrefectureCode = StringUtils.hasText(prefectureCode) ? prefectureCode : null;

        Page<TeamEntity> teamPage = teamRepository.searchPublicTeams(
                effectiveKeyword, effectivePrefecture, effectivePrefectureCode, pageable);

        if (teamPage.isEmpty()) {
            return Page.empty(pageable);
        }

        // N+1 を防ぐために lastPostDate を一括取得してマップに変換する
        Set<Long> teamIds = teamPage.getContent().stream()
                .map(TeamEntity::getId)
                .collect(Collectors.toSet());
        Map<Long, LocalDateTime> lastPostDateMap = buildLastPostDateMap(teamIds);

        List<PublicTeamSearchResultResponse> content = teamPage.getContent().stream()
                .map(team -> new PublicTeamSearchResultResponse(
                        team.getId(),
                        team.getSlug(),
                        team.getName(),
                        team.getIconUrl(),
                        team.getMemberCount() != null ? Math.toIntExact(team.getMemberCount()) : 0,
                        lastPostDateMap.get(team.getId()),
                        team.getPrefectureCode(),
                        team.getCityCode()
                ))
                .toList();

        return new PageImpl<>(content, pageable, teamPage.getTotalElements());
    }

    /**
     * チーム ID 集合に対する最新投稿日時マップを構築する。
     *
     * @param teamIds チーム ID 集合
     * @return teamId → lastPostDate のマップ（投稿なしのチームはエントリなし）
     */
    private Map<Long, LocalDateTime> buildLastPostDateMap(Set<Long> teamIds) {
        List<Object[]> rows = blogPostRepository.findMaxCreatedAtByTeamIdIn(teamIds);
        return rows.stream()
                .filter(row -> row[0] != null && row[1] != null)
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (LocalDateTime) row[1]
                ));
    }
}
