package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.organization.service.OrganizationQueryService;
import com.mannschaft.app.search.SearchContributor;
import com.mannschaft.app.search.SearchCriteria;
import com.mannschaft.app.search.SearchHit;
import com.mannschaft.app.search.SearchQueryText;
import com.mannschaft.app.search.SearchTextHighlighter;
import com.mannschaft.app.search.SearchType;
import com.mannschaft.app.search.SearchViewer;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostedAsType;
import com.mannschaft.app.timeline.repository.TimelinePostKeywordSearchRepository;
import com.mannschaft.app.timeline.repository.TimelinePostKeywordSearchRepository.Condition;
import com.mannschaft.app.timeline.repository.TimelinePostKeywordSearchRepository.DateBound;
import com.mannschaft.app.timeline.repository.TimelinePostKeywordSearchRepository.ScoredPost;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * グローバル検索 POST（タイムライン投稿）種別の {@link SearchContributor}（F04.6 §6、CMP-261116-0722 PR-2）。
 *
 * <p><b>可視性</b>: 既存 {@code GET /timeline/search}（{@code TimelinePostRepository#SEARCH_QUERY}）・
 * {@link TimelinePostVisibilityAccessGuard#isVisible} と同じ scope 述語（PUBLIC・所属 TEAM/ORG・配信祖先組織・
 * 本人 PERSONAL）かつ未削除かつ PUBLISHED。件数と取得は {@link TimelinePostKeywordSearchRepository} の
 * 同一 WHERE 句から出す。VILLAGE・FRIEND_* は対象外。</p>
 *
 * <p><b>所属</b>は集約側 {@link SearchViewer}（GUEST のみ・削除済み・アーカイブ済みスコープを除外済み）を使う。
 * 配下配信の到達範囲は {@link TimelineDeliveryScopeResolver} の唯一の正準実装から求め、到達先の組織のうち
 * 論理削除・アーカイブ済みのものは {@link OrganizationQueryService#findActiveIds} で除く（Reach 自体は
 * 組織の生死を見ないため）。</p>
 *
 * <p><b>スコープ指定時</b>は、そのスコープ（集約側で所属確認済み）に直接属する投稿だけを返す
 * （PUBLIC・PERSONAL・配下配信は含めない）。</p>
 *
 * <p><b>{@code @Transactional} を付けない理由</b>: 配信到達・組織の生死・名前の解決はそれぞれ他ドメインの
 * 公開 Service（自前の読み取りトランザクション）に委ねる。本クラスにトランザクションを張ると、それらの先の
 * 他ドメイン Repository まで timeline のトランザクションが越境する（D-3T）。件数と取得は同一の述語から
 * 別々に発行するため、同一スナップショットである必要はない（集約側も件数と取得を別呼び出しにしている）。</p>
 */
@Component
@RequiredArgsConstructor
public class TimelinePostSearchContributor implements SearchContributor {

    /** {@code CAST(:x AS DATETIME)} に渡す壁時計の書式。 */
    private static final DateTimeFormatter BOUND_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TimelinePostKeywordSearchRepository keywordSearchRepository;
    private final TimelineDeliveryScopeResolver deliveryScopeResolver;
    private final OrganizationQueryService organizationQueryService;
    private final NameResolverService nameResolverService;

    @Override
    public SearchType type() {
        return SearchType.POST;
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
        List<ScoredPost> scored = keywordSearchRepository.find(condition, offset, limit);
        if (scored.isEmpty()) {
            return List.of();
        }
        Set<Long> authorIds = new HashSet<>();
        Set<Long> teamIds = new HashSet<>();
        Set<Long> orgIds = new HashSet<>();
        for (ScoredPost s : scored) {
            if (postedAsUser(s) && s.userId() != null) {
                authorIds.add(s.userId());
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

        List<SearchHit> hits = new ArrayList<>(scored.size());
        for (ScoredPost s : scored) {
            // 返却列はすべて可視性を判定した同じ SQL の行の値（ID で読み直さない。PR-6 検分 P1 の横展開）
            SearchHit.Author author = postedAsUser(s) && s.userId() != null && authorIds.contains(s.userId())
                    ? new SearchHit.Author(s.userId(), displayNames.get(s.userId()), avatarUrls.get(s.userId()))
                    : null;
            hits.add(new SearchHit(SearchType.POST, s.id(), null,
                    SearchTextHighlighter.snippet(s.content(), criteria.terms()), Map.of(), s.relevance(),
                    author, scopeRef(s, teamNames, orgNames),
                    SearchHit.createdAtOfStoredUtc(s.createdAtUtc()),
                    "/timeline/" + s.id(), List.of()));
        }
        return hits;
    }

    /**
     * 可視性述語の材料を組み立てる。該当が原理的に無い場合は null（SQL を発行しない）。
     */
    private Condition condition(SearchCriteria criteria, SearchViewer viewer) {
        String booleanQuery = SearchQueryText.toBooleanModeQuery(criteria.terms());
        if (booleanQuery.isBlank()) {
            return null;
        }
        boolean scopeRestricted = criteria.scopeType() != null;
        // スコープ指定時は集約側で所属確認済みの 1 スコープだけに絞った母集団を使う（所属外なら空になる）
        SearchViewer population = viewer.narrowTo(criteria);
        List<Long> teamIds = population.teamIds();
        List<Long> orgIds = population.organizationIds();
        if (scopeRestricted && teamIds.isEmpty() && orgIds.isEmpty()) {
            return null;
        }
        List<Long> nearOrgIds = List.of();
        List<Long> farOrgIds = List.of();
        if (!scopeRestricted) {
            TimelineDeliveryScopeResolver.Reach reach = deliveryScopeResolver.resolve(teamIds, orgIds);
            if (!reach.isEmpty()) {
                Set<Long> reached = new HashSet<>(reach.nearOrgIds());
                reached.addAll(reach.farOrgIds());
                Set<Long> active = organizationQueryService.findActiveIds(reached);
                nearOrgIds = reach.nearOrgIds().stream().filter(active::contains).toList();
                farOrgIds = reach.farOrgIds().stream().filter(active::contains).toList();
            }
        }
        // 日付境界は閲覧者 TZ で作る（リクエストスレッドで解決済みの値。本メソッドは別スレッドで動く）
        ZoneId zone = criteria.viewerZone();
        return new Condition(booleanQuery, viewer.userId(), safe(teamIds), safe(orgIds), safe(nearOrgIds),
                safe(farOrgIds), scopeRestricted, criteria.authorId(),
                startOf(criteria.dateFrom(), zone),
                criteria.dateTo() == null ? null : startOf(criteria.dateTo().plusDays(1), zone));
    }

    /** {@code IN} 句へ安全に渡せる集合（空ならダミー値で当該条件を無効化する）。 */
    private static List<Long> safe(List<Long> ids) {
        return ids.isEmpty() ? List.of(TimelineDeliveryScopeResolver.EMPTY_IN_SENTINEL) : ids;
    }

    /**
     * 閲覧者 TZ でのその日の開始（壁時計）と、その時点の UTC オフセット。
     *
     * <p>{@link LocalDate#atStartOfDay(ZoneId)} を使うため、0 時が夏時間の切替で存在しない地域でも
     * その日の実在する最初の時刻（例 01:00）を境界にする。</p>
     */
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

    /** チーム／組織名義の投稿は個人の投稿として扱わない（投稿者個人を推定させない）。 */
    private static boolean postedAsUser(ScoredPost s) {
        return PostedAsType.USER.name().equals(s.postedAsType());
    }

    private static SearchHit.ScopeRef scopeRef(ScoredPost s, Map<Long, String> teamNames,
                                               Map<Long, String> orgNames) {
        if (PostScopeType.TEAM.name().equals(s.scopeType())) {
            return new SearchHit.ScopeRef(SearchViewer.TEAM, s.scopeId(), teamNames.get(s.scopeId()));
        }
        if (PostScopeType.ORGANIZATION.name().equals(s.scopeType())) {
            return new SearchHit.ScopeRef(SearchViewer.ORGANIZATION, s.scopeId(), orgNames.get(s.scopeId()));
        }
        // PERSONAL の scope_id は利用者 ID なので返さない
        return new SearchHit.ScopeRef(s.scopeType(), null, null);
    }
}
