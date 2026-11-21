package com.mannschaft.app.filesharing.service;

import com.mannschaft.app.common.MembershipScopeQueryService;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.visibility.RolePriority;
import com.mannschaft.app.filesharing.FileVisibilityRole;
import com.mannschaft.app.filesharing.repository.SharedFileKeywordSearchRepository;
import com.mannschaft.app.filesharing.repository.SharedFileKeywordSearchRepository.BaseCondition;
import com.mannschaft.app.filesharing.repository.SharedFileKeywordSearchRepository.Condition;
import com.mannschaft.app.filesharing.repository.SharedFileKeywordSearchRepository.DateBound;
import com.mannschaft.app.filesharing.repository.SharedFileKeywordSearchRepository.ScopeGroup;
import com.mannschaft.app.filesharing.repository.SharedFileKeywordSearchRepository.ScoredFile;
import com.mannschaft.app.organization.service.OrganizationQueryService;
import com.mannschaft.app.organization.service.OrganizationQueryService.OrganizationListItem;
import com.mannschaft.app.search.SearchContributor;
import com.mannschaft.app.search.SearchCriteria;
import com.mannschaft.app.search.SearchHit;
import com.mannschaft.app.search.SearchQueryText;
import com.mannschaft.app.search.SearchTextHighlighter;
import com.mannschaft.app.search.SearchType;
import com.mannschaft.app.search.SearchViewer;
import com.mannschaft.app.team.service.TeamQueryService;
import com.mannschaft.app.team.service.TeamQueryService.TeamListItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * グローバル検索 FILE（共有ファイル）種別の {@link SearchContributor}（F04.6 §7、CMP-261116-0722 PR-6）。
 *
 * <p><b>可視性</b>（述語の SQL と件数・取得の同一述語は {@link SharedFileKeywordSearchRepository}）:</p>
 * <ul>
 *   <li><b>対象</b>: TEAM / ORGANIZATION スコープのフォルダ配下のファイルだけ。PERSONAL・大会系は対象外。
 *       FILE に公開経路は無い（公開チーム・公開組織でも非所属者には出ない）。</li>
 *   <li><b>所属</b>: 集約側 {@link SearchViewer}（SUPPORTER 以上・GUEST のみ除外・削除/アーカイブ/未稼働除外済み・
 *       SYSTEM_ADMIN は母集団なし）のスコープのうち、既存のファイル閲覧の基本認可
 *       （{@code SharedFolderAccessGuard#authorizeBaseView} → {@code AccessControlService#checkMembership}）と同じく
 *       {@code memberships} の在籍行（{@code left_at IS NULL}）があるものだけ。{@code user_roles} の ADMIN だけで
 *       在籍行の無いスコープは除く。在籍行は {@link MembershipScopeQueryService} でスコープ種別ごとに 1 回ずつ
 *       （スコープ数に依らない）一括取得する。</li>
 *   <li><b>最低可視ロール</b>: ファイル自身の値 AND 根から直属までの全フォルダの値を、<b>そのスコープでの</b>ロールで
 *       満たすこと（ファイル一覧経路はパス上の各フォルダの {@code authorizeView} の後にファイル値で絞るため）。
 *       段は {@code AccessControlService#hasRoleOrAbove} と同じ {@link RolePriority}（ADMINS_AND_ABOVE は ADMIN 以上で
 *       DEPUTY_ADMIN は届かない）。SYSTEM_ADMIN を素通しする既存の {@code applyMinVisibleRole} /
 *       {@code resolveVisibleFileLevels} は使わない。</li>
 *   <li><b>個別権限</b>: ファイル自身またはパス上のフォルダに {@code file_permissions} 行があるファイルは、件数と同じ
 *       述語で行ごとの被付与判定を確定できないため誰にも出さない（fail-closed）。権限行で可視範囲は広げない。</li>
 * </ul>
 * <p>スコープ指定時は集約側で所属確認済みの 1 スコープに絞った母集団だけを使う（所属外なら SQL を発行せず 0 件）。</p>
 *
 * <p><b>{@code @Transactional} を付けない理由</b>: 在籍行・スコープ名・slug・利用者名の解決は他ドメインの公開 Service
 * （自前の読み取りトランザクション）に委ねる。本クラスにトランザクションを張ると、その先の他ドメイン Repository まで
 * filesharing のトランザクションが越境する（D-3T）。件数と取得は別呼び出しで、同一スナップショットである必要はない。</p>
 */
@Component
@RequiredArgsConstructor
public class SharedFileSearchContributor implements SearchContributor {

    private static final DateTimeFormatter BOUND_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SharedFileKeywordSearchRepository keywordSearchRepository;
    private final MembershipScopeQueryService membershipScopeQueryService;
    private final TeamQueryService teamQueryService;
    private final OrganizationQueryService organizationQueryService;
    private final NameResolverService nameResolverService;

    @Override
    public SearchType type() {
        return SearchType.FILE;
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
        List<ScoredFile> scored = keywordSearchRepository.find(condition, offset, limit);
        if (scored.isEmpty()) {
            return List.of();
        }
        Set<Long> authorIds = new HashSet<>();
        Set<Long> teamIds = new HashSet<>();
        Set<Long> orgIds = new HashSet<>();
        for (ScoredFile s : scored) {
            if (s.createdBy() != null) {
                authorIds.add(s.createdBy());
            }
            (s.team() ? teamIds : orgIds).add(s.scopeId());
        }
        Map<Long, String> displayNames = nameResolverService.resolveUserDisplayNames(authorIds);
        Map<Long, String> avatarUrls = nameResolverService.resolveUserAvatarUrls(authorIds);
        Map<Long, TeamListItem> teams = teamQueryService.findListItemsByIds(teamIds);
        Map<Long, OrganizationListItem> orgs = organizationQueryService.findListItemsByIds(orgIds);

        List<SearchHit> hits = new ArrayList<>(scored.size());
        for (ScoredFile s : scored) {
            // 返却列はすべて可視性を判定した同じ SQL の行の値（ID で読み直さない。PR-6 検分 P1）
            Long authorId = s.createdBy();
            SearchHit.Author author = authorId == null ? null
                    : new SearchHit.Author(authorId, displayNames.get(authorId), avatarUrls.get(authorId));
            Map<String, List<Integer>> highlights = new HashMap<>();
            List<Integer> range = SearchTextHighlighter.titleRange(s.name(), criteria.terms());
            if (range != null) {
                highlights.put("title", range);
            }
            String scopeName;
            String slug;
            if (s.team()) {
                TeamListItem t = teams.get(s.scopeId());
                scopeName = t == null ? null : t.name();
                slug = t == null ? null : t.slug();
            } else {
                OrganizationListItem o = orgs.get(s.scopeId());
                scopeName = o == null ? null : o.name();
                slug = o == null ? null : o.slug();
            }
            SearchHit.ScopeRef scope = new SearchHit.ScopeRef(
                    s.team() ? SearchViewer.TEAM : SearchViewer.ORGANIZATION, s.scopeId(), scopeName);
            // FE の実在ルート pages/teams/[slug]/files.vue / organizations/[slug]/files.vue（フォルダ指定のクエリは無い）。
            // slug を解決できないときはリンク不可（null）とし、結果と件数の一致は崩さない
            String url = slug == null || slug.isBlank() ? null
                    : (s.team() ? "/teams/" : "/organizations/") + encodeUriComponent(slug) + "/files";
            hits.add(new SearchHit(SearchType.FILE, s.id(), s.name(),
                    SearchTextHighlighter.snippet(s.description(), criteria.terms()), highlights,
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
        if (population.scopes().isEmpty()) {
            return null;
        }
        Set<Long> enrolledTeams = population.teamIds().isEmpty() ? Set.of()
                : Set.copyOf(membershipScopeQueryService.findCurrentMembershipTeamIds(viewer.userId()));
        Set<Long> enrolledOrgs = population.organizationIds().isEmpty() ? Set.of()
                : Set.copyOf(membershipScopeQueryService.findCurrentMembershipOrganizationIds(viewer.userId()));
        List<ScopeGroup> groups = scopeGroups(population, enrolledTeams, enrolledOrgs);
        if (groups.isEmpty()) {
            return null;
        }
        ZoneId zone = criteria.viewerZone();
        BaseCondition base = new BaseCondition(booleanQuery, criteria.authorId(),
                startOf(criteria.dateFrom(), zone),
                criteria.dateTo() == null ? null : startOf(criteria.dateTo().plusDays(1), zone));
        return new Condition(base, groups);
    }

    /**
     * 在籍行のある母集団スコープを、スコープ単位のロールで満たす最低可視ロールの集合に写し、同じ集合の同種スコープを
     * 1 グループにまとめる（グループ数はスコープ数でなくロール段の数で頭打ち）。
     */
    static List<ScopeGroup> scopeGroups(SearchViewer population, Set<Long> enrolledTeams, Set<Long> enrolledOrgs) {
        Map<String, List<Long>> idsByKey = new LinkedHashMap<>();
        Map<String, List<String>> levelsByKey = new HashMap<>();
        for (SearchViewer.ViewerScope scope : population.scopes()) {
            boolean team = SearchViewer.TEAM.equals(scope.scopeType());
            if (!team && !SearchViewer.ORGANIZATION.equals(scope.scopeType())) {
                continue;
            }
            if (!(team ? enrolledTeams : enrolledOrgs).contains(scope.scopeId())) {
                continue;
            }
            List<String> levels = levelsFor(scope.roleName());
            if (levels.isEmpty()) {
                continue;
            }
            String key = (team ? "T:" : "O:") + String.join(",", levels);
            idsByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(scope.scopeId());
            levelsByKey.put(key, levels);
        }
        List<ScopeGroup> groups = new ArrayList<>(idsByKey.size());
        idsByKey.forEach((key, ids) -> groups.add(
                new ScopeGroup(key.startsWith("T:"), List.copyOf(ids), levelsByKey.get(key))));
        return groups;
    }

    /**
     * スコープでのロールが満たす {@link FileVisibilityRole} の名前を返す（{@code AccessControlService#hasRoleOrAbove}
     * と同じ {@link RolePriority} の段）。ロール不明・SYSTEM_ADMIN（スコープ別ロールではない）・SUPPORTER 未満は空。
     */
    static List<String> levelsFor(String roleName) {
        if (!RolePriority.isRegistered(roleName) || "SYSTEM_ADMIN".equals(roleName)
                || !RolePriority.isAtLeast(roleName, FileVisibilityRole.SUPPORTERS_AND_ABOVE.toRequiredRoleName())) {
            return List.of();
        }
        List<String> levels = new ArrayList<>();
        for (FileVisibilityRole level : FileVisibilityRole.values()) {
            if (RolePriority.isAtLeast(roleName, level.toRequiredRoleName())) {
                levels.add(level.name());
            }
        }
        return List.copyOf(levels);
    }

    /** JavaScript の {@code encodeURIComponent} と同じ規則で符号化する。 */
    static String encodeUriComponent(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%21", "!")
                .replace("%27", "'")
                .replace("%28", "(")
                .replace("%29", ")")
                .replace("%7E", "~");
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
