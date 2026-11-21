package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.repository.BlogPostKeywordSearchRepository;
import com.mannschaft.app.cms.repository.BlogPostKeywordSearchRepository.BaseCondition;
import com.mannschaft.app.cms.repository.BlogPostKeywordSearchRepository.CandidateScopes;
import com.mannschaft.app.cms.repository.BlogPostKeywordSearchRepository.Condition;
import com.mannschaft.app.cms.repository.BlogPostKeywordSearchRepository.DateBound;
import com.mannschaft.app.cms.repository.BlogPostKeywordSearchRepository.ScopeGroup;
import com.mannschaft.app.cms.repository.BlogPostKeywordSearchRepository.ScoredPost;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.visibility.RolePriority;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.mapping.CmsVisibilityMapper;
import com.mannschaft.app.organization.service.OrganizationQueryService;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.service.ContentGateAccessService;
import com.mannschaft.app.search.SearchContributor;
import com.mannschaft.app.search.SearchCriteria;
import com.mannschaft.app.search.SearchHit;
import com.mannschaft.app.search.SearchQueryText;
import com.mannschaft.app.search.SearchTextHighlighter;
import com.mannschaft.app.search.SearchType;
import com.mannschaft.app.search.SearchViewer;
import com.mannschaft.app.team.service.TeamQueryService;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * グローバル検索 ARTICLE（ブログ記事）種別の {@link SearchContributor}（F04.6 §7、CMP-261116-0722 PR-5）。
 *
 * <p><b>可視性</b>（述語の SQL と件数・取得の同一 WHERE は {@link BlogPostKeywordSearchRepository}）:</p>
 * <ul>
 *   <li><b>共通</b>: 未削除・{@code PUBLISHED}・公開日時を過ぎた、チーム記事か組織記事。個人ブログは対象外。
 *       課金ゲート（{@code content_payment_gates}）付きの記事は、閲覧者が支払い条件を満たさないものを
 *       payment の公開 Service {@link ContentGateAccessService#findUnsatisfiedGatedContentIds} から受け取った ID 集合で
 *       件数・取得の同じ WHERE から除外する。判定は記事を開くときと同じ正準判定（受益者キー・全ゲート AND・有効期限と猶予・
 *       記事と同スコープの項目）で、SYSTEM_ADMIN／スコープ ADMIN のバイパスはしない。{@code is_title_hidden=FALSE} の
 *       「鍵付きタイトルだけ見せる」表示は、本文由来の snippet を返す検索では行わず、未充足なら出さない。</li>
 *   <li><b>所属経路</b>: 集約側 {@link SearchViewer}（SUPPORTER 以上・GUEST のみ除外・削除/アーカイブ/未稼働除外済み）の
 *       各スコープについて、<b>そのスコープでの</b>ロールから可視レベルを決める（全所属先の和集合で判定しない）。
 *       可視レベルの段は {@code MembershipBatchQueryService#resolveVisibleLevels} と同じ
 *       （{@link RolePriority} で SUPPORTER / MEMBER / ADMIN 以上を判定。DEPUTY_ADMIN は ADMINS_AND_ABOVE に届かない）で、
 *       cms の visibility への対応は {@link CmsVisibilityMapper} に従う。対象は {@link #SEARCHABLE} の 6 値だけで、
 *       PRIVATE / FOLLOWERS_ONLY / CUSTOM_TEMPLATE / CUSTOM は行ごとの判定が要るため作成者・ADMIN にも出さない。
 *       同じ可視レベル集合のスコープは 1 グループにまとめ、スコープ数ではなくロール段の数だけの OR にする。</li>
 *   <li><b>公開経路</b>（スコープ指定なしのときだけ）: {@code visibility = PUBLIC} かつ {@code public_visible = TRUE}
 *       の記事の親スコープを候補として列挙し、team / organization の公開 Service（{@link TeamQueryService#findPublicIds} /
 *       {@link OrganizationQueryService#findPublicIds}。未ログイン公開ページ・sitemap と同じ PUBLIC・ACTIVE・未アーカイブ・
 *       未削除）で公開と判定された ID だけを受け取る。所属経路と同じ WHERE の OR に入れるため二重に数えない。
 *       スコープ指定時は混ぜない（他スコープの存在を明かさない）。</li>
 * </ul>
 * <p>SYSTEM_ADMIN もバイパスしない（集約側が母集団を空にする）。</p>
 *
 * <p><b>{@code @Transactional} を付けない理由</b>: 公開スコープ判定・未充足ゲート ID・名前の解決は他ドメインの公開 Service
 * （自前の読み取りトランザクション）に委ねる。本クラスにトランザクションを張ると、その先の他ドメイン Repository まで
 * cms のトランザクションが越境する（D-3T）。件数と取得は別呼び出しで、同一スナップショットである必要はない。</p>
 */
@Component
@RequiredArgsConstructor
public class BlogPostSearchContributor implements SearchContributor {

    private static final DateTimeFormatter BOUND_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 行に依存せず判定できる、検索対象の visibility（これ以外は誰にも出さない）。 */
    static final List<Visibility> SEARCHABLE = List.of(
            Visibility.PUBLIC,
            Visibility.SCOPE_AFFILIATED,
            Visibility.SUPPORTERS_AND_ABOVE,
            Visibility.MEMBERS_ONLY,
            Visibility.MEMBERS_AND_ABOVE,
            Visibility.ADMINS_AND_ABOVE);

    private final BlogPostKeywordSearchRepository keywordSearchRepository;
    private final TeamQueryService teamQueryService;
    private final OrganizationQueryService organizationQueryService;
    private final ContentGateAccessService contentGateAccessService;
    private final NameResolverService nameResolverService;

    @Override
    public SearchType type() {
        return SearchType.ARTICLE;
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
            if (s.authorId() != null) {
                authorIds.add(s.authorId());
            }
            if (s.teamId() != null) {
                teamIds.add(s.teamId());
            } else if (s.organizationId() != null) {
                orgIds.add(s.organizationId());
            }
        }
        Map<Long, String> displayNames = nameResolverService.resolveUserDisplayNames(authorIds);
        Map<Long, String> avatarUrls = nameResolverService.resolveUserAvatarUrls(authorIds);
        Map<Long, String> teamNames = nameResolverService.resolveTeamNames(teamIds);
        Map<Long, String> orgNames = nameResolverService.resolveOrganizationNames(orgIds);

        List<SearchHit> hits = new ArrayList<>(scored.size());
        for (ScoredPost s : scored) {
            // 返却列はすべて可視性を判定した同じ SQL の行の値（ID で読み直さない。PR-6 検分 P1 の横展開）
            boolean team = s.teamId() != null;
            Long scopeId = team ? s.teamId() : s.organizationId();
            if (scopeId == null || s.slug() == null || s.slug().isBlank()) {
                // 遷移先を組み立てられない記事は出さない（SQL 側で除外済みのはずだが安全側）
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
                    ? new SearchHit.ScopeRef(SearchViewer.TEAM, scopeId, teamNames.get(scopeId))
                    : new SearchHit.ScopeRef(SearchViewer.ORGANIZATION, scopeId, orgNames.get(scopeId));
            String url = "/blog/posts/" + encodeUriComponent(s.slug())
                    + (team ? "?teamId=" : "?organizationId=") + scopeId;
            hits.add(new SearchHit(SearchType.ARTICLE, s.id(), s.title(),
                    SearchTextHighlighter.snippet(plainText(s.body()), criteria.terms()), highlights,
                    s.relevance(), author, scope, SearchHit.createdAtOfStoredUtc(s.createdAtUtc()), url, List.of()));
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
        List<ScopeGroup> groups = scopeGroups(population);
        boolean scoped = criteria.scopeType() != null;
        if (scoped && groups.isEmpty()) {
            return null;
        }
        ZoneId zone = criteria.viewerZone();
        BaseCondition base = new BaseCondition(booleanQuery, criteria.authorId(),
                startOf(criteria.dateFrom(), zone),
                criteria.dateTo() == null ? null : startOf(criteria.dateTo().plusDays(1), zone));

        List<Long> publicTeamIds = List.of();
        List<Long> publicOrgIds = List.of();
        if (!scoped) {
            // 公開経路: 一致した公開記事の親スコープだけを、team / organization の公開 Service で判定する
            CandidateScopes candidates = keywordSearchRepository.findPublicCandidateScopes(base);
            publicTeamIds = sorted(teamQueryService.findPublicIds(candidates.teamIds()));
            publicOrgIds = sorted(organizationQueryService.findPublicIds(candidates.orgIds()));
        }
        if (groups.isEmpty() && publicTeamIds.isEmpty() && publicOrgIds.isEmpty()) {
            return null;
        }
        // 閲覧者が支払い条件を満たさない課金ゲート付き記事（記事を開くときと同じ正準判定。バイパスなし）
        List<Long> excluded = sorted(
                contentGateAccessService.findUnsatisfiedGatedContentIds(viewer.userId(), ContentGateType.POST));
        return new Condition(base, groups, publicTeamIds, publicOrgIds, excluded);
    }

    /**
     * 所属スコープをスコープ単位のロールで可視レベル集合に写し、同じ集合の同種スコープを 1 グループにまとめる
     * （グループ数はスコープ数でなくロール段の数で頭打ち）。
     */
    static List<ScopeGroup> scopeGroups(SearchViewer population) {
        Map<String, List<Long>> idsByKey = new LinkedHashMap<>();
        Map<String, List<String>> visibilitiesByKey = new HashMap<>();
        for (SearchViewer.ViewerScope scope : population.scopes()) {
            boolean team = SearchViewer.TEAM.equals(scope.scopeType());
            if (!team && !SearchViewer.ORGANIZATION.equals(scope.scopeType())) {
                continue;
            }
            List<String> visibilities = visibilitiesFor(scope.roleName());
            if (visibilities.isEmpty()) {
                continue;
            }
            String key = (team ? "T:" : "O:") + String.join(",", visibilities);
            idsByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(scope.scopeId());
            visibilitiesByKey.put(key, visibilities);
        }
        List<ScopeGroup> groups = new ArrayList<>(idsByKey.size());
        idsByKey.forEach((key, ids) -> groups.add(
                new ScopeGroup(key.startsWith("T:"), List.copyOf(ids), visibilitiesByKey.get(key))));
        return groups;
    }

    /**
     * スコープでのロールから、そのスコープの記事で閲覧できる visibility を返す。
     *
     * <p>段は {@code MembershipBatchQueryService#resolveVisibleLevels} と同じ（SUPPORTER 以上で PUBLIC /
     * SCOPE_AFFILIATED / SUPPORTERS_AND_ABOVE、MEMBER 以上で MEMBERS_AND_ABOVE、ADMIN 以上で ADMINS_AND_ABOVE）。
     * 母集団は SUPPORTER 以上の直接所属に限られるため、SCOPE_AFFILIATED は母集団に入った時点で満たす。
     * ロール不明・GUEST・SYSTEM_ADMIN（スコープ別ロールではない）は空。</p>
     */
    static List<String> visibilitiesFor(String roleName) {
        if (!RolePriority.isRegistered(roleName) || "SYSTEM_ADMIN".equals(roleName)
                || !RolePriority.isAtLeast(roleName, "SUPPORTER")) {
            return List.of();
        }
        Set<StandardVisibility> levels = EnumSet.of(StandardVisibility.PUBLIC, StandardVisibility.SCOPE_AFFILIATED,
                StandardVisibility.SUPPORTERS_AND_ABOVE);
        if (RolePriority.isAtLeast(roleName, "MEMBER")) {
            levels.add(StandardVisibility.MEMBERS_AND_ABOVE);
        }
        if (RolePriority.isAtLeast(roleName, "ADMIN")) {
            levels.add(StandardVisibility.ADMINS_AND_ABOVE);
        }
        List<String> result = new ArrayList<>();
        for (Visibility v : SEARCHABLE) {
            if (levels.contains(CmsVisibilityMapper.toStandard(v))) {
                result.add(v.name());
            }
        }
        return List.copyOf(result);
    }

    /** 本文の HTML をタグ除去したプレーンテキストにする（script / style の中身も落とす）。 */
    static String plainText(String body) {
        if (body == null) {
            return null;
        }
        return Jsoup.parseBodyFragment(body).text();
    }

    /** JavaScript の {@code encodeURIComponent} と同じ規則で符号化する（FE blogPostRoute.ts と遷移先を揃える）。 */
    static String encodeUriComponent(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%21", "!")
                .replace("%27", "'")
                .replace("%28", "(")
                .replace("%29", ")")
                .replace("%7E", "~");
    }

    private static List<Long> sorted(Set<Long> ids) {
        return ids.stream().sorted().toList();
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
