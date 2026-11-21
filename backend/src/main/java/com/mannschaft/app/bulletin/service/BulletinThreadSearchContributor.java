package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.repository.BulletinThreadKeywordSearchRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadKeywordSearchRepository.Condition;
import com.mannschaft.app.bulletin.repository.BulletinThreadKeywordSearchRepository.DateBound;
import com.mannschaft.app.bulletin.repository.BulletinThreadKeywordSearchRepository.ScoredThread;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.search.SearchContributor;
import com.mannschaft.app.search.SearchCriteria;
import com.mannschaft.app.search.SearchHit;
import com.mannschaft.app.search.SearchQueryText;
import com.mannschaft.app.search.SearchTextHighlighter;
import com.mannschaft.app.search.SearchType;
import com.mannschaft.app.search.SearchViewer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * グローバル検索 THREAD（掲示板スレッド）種別の {@link SearchContributor}（F04.6 §7、CMP-261116-0722 PR-4）。
 *
 * <p><b>可視性</b>: スレッドの {@code scope_type}（TEAM / ORGANIZATION）と {@code scope_id} のペアが、集約側
 * {@link SearchViewer}（GUEST のみ・削除済み・アーカイブ済みスコープを除外済み）の母集団に含まれ、かつ
 * 論理削除・アーカイブされていないこと。PERSONAL / VILLAGE / TOURNAMENT 系は対象外。述語の詳細と件数・取得の
 * 同一 WHERE 句は {@link BulletinThreadKeywordSearchRepository} を参照。<b>返信は検索対象にしない。</b>
 * スコープ指定時は {@link SearchViewer#narrowTo} で絞った 1 スコープだけを対象にする。</p>
 *
 * <p><b>{@code @Transactional} を付けない理由</b>: 名前・slug の解決は他ドメインの公開 Service（自前の読み取り
 * トランザクション）に委ねる。本クラスにトランザクションを張ると、その先の他ドメイン Repository まで bulletin の
 * トランザクションが越境する（D-3T）。件数と取得は別呼び出しで、同一スナップショットである必要はない。</p>
 */
@Component
@RequiredArgsConstructor
public class BulletinThreadSearchContributor implements SearchContributor {

    private static final DateTimeFormatter BOUND_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** IN 句を空にしないためのダミー ID（実在しない）。 */
    private static final Long EMPTY_IN_SENTINEL = -1L;

    private final BulletinThreadKeywordSearchRepository keywordSearchRepository;
    private final NameResolverService nameResolverService;

    @Override
    public SearchType type() {
        return SearchType.THREAD;
    }

    @Override
    public long count(SearchCriteria criteria, SearchViewer viewer) {
        Condition condition = condition(criteria, viewer);
        if (condition == null) {
            return 0L;
        }
        return keywordSearchRepository.count(condition);
    }

    @Override
    public List<SearchHit> find(SearchCriteria criteria, SearchViewer viewer, int offset, int limit) {
        Condition condition = condition(criteria, viewer);
        if (condition == null) {
            return List.of();
        }
        List<ScoredThread> scored = keywordSearchRepository.find(condition, offset, limit);
        if (scored.isEmpty()) {
            return List.of();
        }
        Set<Long> authorIds = new HashSet<>();
        Set<Long> teamIds = new HashSet<>();
        Set<Long> orgIds = new HashSet<>();
        for (ScoredThread s : scored) {
            if (s.authorId() != null) {
                authorIds.add(s.authorId());
            }
            if (SearchViewer.TEAM.equals(s.scopeType())) {
                teamIds.add(s.scopeId());
            } else if (SearchViewer.ORGANIZATION.equals(s.scopeType())) {
                orgIds.add(s.scopeId());
            }
        }
        Map<Long, String> displayNames = nameResolverService.resolveUserDisplayNames(authorIds);
        Map<Long, String> avatarUrls = nameResolverService.resolveUserAvatarUrls(authorIds);
        Map<Long, String> teamNames = nameResolverService.resolveTeamNames(teamIds);
        Map<Long, String> orgNames = nameResolverService.resolveOrganizationNames(orgIds);
        Map<Long, String> teamSlugs = nameResolverService.resolveTeamSlugs(teamIds);
        Map<Long, String> orgSlugs = nameResolverService.resolveOrganizationSlugs(orgIds);

        List<SearchHit> hits = new ArrayList<>(scored.size());
        for (ScoredThread s : scored) {
            // 返却列はすべて可視性を判定した同じ SQL の行の値（ID で読み直さない。PR-6 検分 P1 の横展開）
            boolean team = SearchViewer.TEAM.equals(s.scopeType());
            String slug = (team ? teamSlugs : orgSlugs).get(s.scopeId());
            if (slug == null) {
                // 遷移先を組み立てられない（スコープが取得時点で消えた等）結果は出さない
                continue;
            }
            Long authorId = s.authorId();
            SearchHit.Author author = authorId == null ? null
                    : new SearchHit.Author(authorId, displayNames.get(authorId), avatarUrls.get(authorId));
            Map<String, List<Integer>> highlights = new HashMap<>();
            List<Integer> range = SearchTextHighlighter.titleRange(s.title(), criteria.terms());
            if (range != null) {
                highlights.put("title", range);
            }
            SearchHit.ScopeRef scope = team
                    ? new SearchHit.ScopeRef(SearchViewer.TEAM, s.scopeId(), teamNames.get(s.scopeId()))
                    : new SearchHit.ScopeRef(SearchViewer.ORGANIZATION, s.scopeId(), orgNames.get(s.scopeId()));
            String url = (team ? "/teams/" : "/organizations/") + slug + "/bulletin?threadId=" + s.id();
            hits.add(new SearchHit(SearchType.THREAD, s.id(), s.title(),
                    SearchTextHighlighter.snippet(s.body(), criteria.terms()), highlights, s.relevance(),
                    author, scope, SearchHit.createdAtOfStoredUtc(s.createdAtUtc()), url, List.of()));
        }
        return hits;
    }

    /** 可視性述語の材料を組み立てる。該当が原理的に無い場合は null（SQL を発行しない）。 */
    private Condition condition(SearchCriteria criteria, SearchViewer viewer) {
        String booleanQuery = SearchQueryText.toBooleanModeQuery(criteria.terms());
        if (booleanQuery.isBlank()) {
            return null;
        }
        // スコープ指定時は集約側で所属確認済みの 1 スコープだけに絞った母集団を使う（所属外なら空になる）
        SearchViewer population = viewer.narrowTo(criteria);
        List<Long> teamIds = population.teamIds();
        List<Long> orgIds = population.organizationIds();
        if (teamIds.isEmpty() && orgIds.isEmpty()) {
            return null;
        }
        ZoneId zone = criteria.viewerZone();
        return new Condition(booleanQuery, safe(teamIds), safe(orgIds), criteria.authorId(),
                startOf(criteria.dateFrom(), zone),
                criteria.dateTo() == null ? null : startOf(criteria.dateTo().plusDays(1), zone));
    }

    private static List<Long> safe(List<Long> ids) {
        return ids.isEmpty() ? List.of(EMPTY_IN_SENTINEL) : ids;
    }

    private static DateBound startOf(LocalDate date, ZoneId zone) {
        if (date == null) {
            return null;
        }
        ZonedDateTime start = date.atStartOfDay(zone);
        ZoneOffset offset = start.getOffset();
        int totalMinutes = offset.getTotalSeconds() / 60;
        String sign = totalMinutes < 0 ? "-" : "+";
        int abs = Math.abs(totalMinutes);
        return new DateBound(start.toLocalDateTime().format(BOUND_FORMAT),
                String.format("%s%02d:%02d", sign, abs / 60, abs % 60));
    }
}
