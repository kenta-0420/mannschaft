package com.mannschaft.app.activity.service;

import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.ActivityVisibility;
import com.mannschaft.app.activity.repository.ActivityResultKeywordSearchRepository;
import com.mannschaft.app.activity.repository.ActivityResultKeywordSearchRepository.ActivityRow;
import com.mannschaft.app.activity.repository.ActivityResultKeywordSearchRepository.AutomaticReference;
import com.mannschaft.app.activity.repository.ActivityResultKeywordSearchRepository.BaseCondition;
import com.mannschaft.app.activity.repository.ActivityResultKeywordSearchRepository.Condition;
import com.mannschaft.app.activity.repository.ActivityResultKeywordSearchRepository.DateBound;
import com.mannschaft.app.activity.repository.ActivityResultKeywordSearchRepository.ScopeGroup;
import com.mannschaft.app.common.MembershipScopeQueryService;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.visibility.RolePriority;
import com.mannschaft.app.common.visibility.mapping.ActivityVisibilityMapper;
import com.mannschaft.app.schedule.dto.ScheduleActivitySource;
import com.mannschaft.app.schedule.service.ScheduleActivityVisibilityService;
import com.mannschaft.app.search.SearchContributor;
import com.mannschaft.app.search.SearchCriteria;
import com.mannschaft.app.search.SearchHit;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * グローバル検索 ACTIVITY（活動記録）種別の {@link SearchContributor}（F04.6 §7、CMP-261116-0722 PR-7）。
 *
 * <p><b>可視性</b>（述語の SQL と件数・取得の同一述語は {@link ActivityResultKeywordSearchRepository}）:</p>
 * <ul>
 *   <li><b>対象</b>: TEAM / ORGANIZATION スコープの、未削除かつ {@code PUBLISHED} の記録だけ。COMMITTEE は
 *       {@code ActivityScopeAccessGuard} がロール基盤の解決対象外として fail-closed にしているため対象外。
 *       DRAFT は作成者本人・ADMIN・SYSTEM_ADMIN を含め誰にも出さない。公開経路は無い。</li>
 *   <li><b>所属</b>: 集約側 {@link SearchViewer}（GUEST のみ除外・削除/アーカイブ/未稼働除外済み）のスコープのうち、
 *       既存一覧の入口 {@code ActivityScopeAccessGuard#checkMembership} と同じく {@code memberships} の在籍行がある
 *       ものだけ。{@code user_roles} の ADMIN だけで在籍行の無いスコープは除く。在籍行は
 *       {@link MembershipScopeQueryService} でスコープ種別ごとに 1 回ずつ一括取得する。</li>
 *   <li><b>可視レベル</b>: <b>そのスコープでの</b>ロール（{@link SearchViewer.ViewerScope#roleName()}）で判定する
 *       （和集合で判定しない）。{@link ActivityVisibilityMapper#toStandard} の写像に従い、PUBLIC は SUPPORTER 以上、
 *       MEMBERS_ONLY（MEMBERS_AND_ABOVE）は MEMBER 以上（段は {@link RolePriority}）。SYSTEM_ADMIN は
 *       スコープ別ロールではないので何も満たさない（既存一覧の {@code viewerIsSystemAdmin} 貫通は使わない）。</li>
 *   <li><b>予定から自動生成された記録</b>: 既存 {@code AutomaticActivityListService#list} と同方式。キーワード・
 *       スコープ・可視レベルに一致する自動生成記録の参照を 1 回で集め、元予定を
 *       {@link ScheduleActivityVisibilityService#visibleSources} で一括判定し、元予定が可視でそのスコープが記録の
 *       スコープと一致する ID だけを許可する。許可の判定は作成者・スコープ ADMIN にも例外なく課す
 *       （{@code AutomaticActivityActualPolicy} の作成者・ADMIN 例外は検索では使わない＝安全側）。件数と取得は
 *       同じ 1 本の SQL で「手動記録 OR 許可 ID」に絞る（許可 ID が空なら番兵で何にも一致させない）。</li>
 * </ul>
 * <p>スコープ指定時は集約側で所属確認済みの 1 スコープに絞った母集団だけを使う（所属外なら SQL を発行せず 0 件）。</p>
 *
 * <p><b>TOCTOU 回避</b>: 結果の題名・説明・スコープ・作成者・作成日時は、可視性を判定した同じ SQL の SELECT 列で
 * 受け取る（ID で再取得しない）。名前・アバターは表示用の補助情報としてのみ別途一括解決する。</p>
 *
 * <p><b>{@code @Transactional} を付けない理由</b>: 在籍行・元予定の可視性・利用者名・スコープ名の解決は他ドメインの
 * 公開 Service（自前の読み取りトランザクション）に委ねる。本クラスにトランザクションを張ると、その先の他ドメイン
 * Repository まで activity のトランザクションが越境する（D-3T）。件数と取得は別呼び出しで、同一スナップショットで
 * ある必要はない（どちらも同じ述語で判定する）。</p>
 */
@Component
@RequiredArgsConstructor
public class ActivityResultSearchContributor implements SearchContributor {

    private static final DateTimeFormatter BOUND_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ActivityResultKeywordSearchRepository keywordSearchRepository;
    private final MembershipScopeQueryService membershipScopeQueryService;
    private final ScheduleActivityVisibilityService scheduleActivityVisibilityService;
    private final NameResolverService nameResolverService;

    @Override
    public SearchType type() {
        return SearchType.ACTIVITY;
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
        List<ActivityRow> rows = keywordSearchRepository.find(condition, offset, limit);
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> authorIds = new HashSet<>();
        Set<Long> teamIds = new HashSet<>();
        Set<Long> orgIds = new HashSet<>();
        for (ActivityRow r : rows) {
            if (r.createdBy() != null) {
                authorIds.add(r.createdBy());
            }
            (SearchViewer.TEAM.equals(r.scopeType()) ? teamIds : orgIds).add(r.scopeId());
        }
        Map<Long, String> displayNames = nameResolverService.resolveUserDisplayNames(authorIds);
        Map<Long, String> avatarUrls = nameResolverService.resolveUserAvatarUrls(authorIds);
        Map<Long, String> teamNames = nameResolverService.resolveTeamNames(teamIds);
        Map<Long, String> orgNames = nameResolverService.resolveOrganizationNames(orgIds);

        List<SearchHit> hits = new ArrayList<>(rows.size());
        for (ActivityRow r : rows) {
            // 返却列はすべて可視性を判定した同じ SQL の行の値（ID で読み直さない。PR-6 検分 P1）
            boolean team = SearchViewer.TEAM.equals(r.scopeType());
            if (!team && !SearchViewer.ORGANIZATION.equals(r.scopeType())) {
                // SQL 側で TEAM / ORGANIZATION に限定済みだが安全側
                continue;
            }
            Long authorId = r.createdBy();
            SearchHit.Author author = authorId == null ? null
                    : new SearchHit.Author(authorId, displayNames.get(authorId), avatarUrls.get(authorId));
            Map<String, List<Integer>> highlights = new HashMap<>();
            List<Integer> range = SearchTextHighlighter.titleRange(r.title(), criteria.terms());
            if (range != null) {
                highlights.put("title", range);
            }
            SearchHit.ScopeRef scope = team
                    ? new SearchHit.ScopeRef(SearchViewer.TEAM, r.scopeId(), teamNames.get(r.scopeId()))
                    : new SearchHit.ScopeRef(SearchViewer.ORGANIZATION, r.scopeId(), orgNames.get(r.scopeId()));
            // FE の現役の認証済み詳細ルート pages/activities/[id].vue
            String url = "/activities/" + r.id();
            hits.add(new SearchHit(SearchType.ACTIVITY, r.id(), r.title(),
                    SearchTextHighlighter.snippet(r.description(), criteria.terms()), highlights,
                    null, author, scope, SearchHit.createdAtOfStoredUtc(r.createdAtUtc()), url, List.of()));
        }
        return hits;
    }

    /** 可視性述語の材料を組み立てる。該当が原理的に無い場合は null（SQL を発行しない）。 */
    private Condition condition(SearchCriteria criteria, SearchViewer viewer) {
        if (criteria.terms() == null || criteria.terms().isEmpty()) {
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
        BaseCondition base = new BaseCondition(List.copyOf(criteria.terms()), criteria.authorId(),
                startOf(criteria.dateFrom(), zone),
                criteria.dateTo() == null ? null : startOf(criteria.dateTo().plusDays(1), zone));
        return new Condition(base, groups, allowedAutomaticIds(base, groups, viewer.userId()));
    }

    /**
     * キーワードに一致する自動生成記録のうち、元予定が閲覧者に可視で、元予定のスコープが記録のスコープと一致するものの
     * ID を返す（参照の列挙 1 回＋元予定の一括判定 1 回。ループ内で問い合わせない）。作成者・ADMIN の例外は設けない。
     */
    private Set<Long> allowedAutomaticIds(BaseCondition base, List<ScopeGroup> groups, Long userId) {
        List<AutomaticReference> references = keywordSearchRepository.findAutomaticReferences(base, groups);
        if (references.isEmpty()) {
            return Set.of();
        }
        Set<Long> scheduleIds = new HashSet<>();
        for (AutomaticReference r : references) {
            if (r.scheduleId() != null) {
                scheduleIds.add(r.scheduleId());
            }
        }
        if (scheduleIds.isEmpty()) {
            return Set.of();
        }
        Map<Long, ScheduleActivitySource> sources =
                scheduleActivityVisibilityService.visibleSources(scheduleIds, userId);
        Set<Long> allowed = new HashSet<>();
        for (AutomaticReference r : references) {
            ScheduleActivitySource source = r.scheduleId() == null ? null : sources.get(r.scheduleId());
            if (source != null && Objects.equals(r.scopeType(), source.scopeType())
                    && Objects.equals(r.scopeId(), source.scopeId())) {
                allowed.add(r.id());
            }
        }
        return Set.copyOf(allowed);
    }

    /**
     * 在籍行のある母集団スコープを、スコープ単位のロールで閲覧できる visibility の集合に写し、同じ集合の同種スコープを
     * 1 グループにまとめる（グループ数はスコープ数でなくロール段の数で頭打ち）。
     */
    static List<ScopeGroup> scopeGroups(SearchViewer population, Set<Long> enrolledTeams, Set<Long> enrolledOrgs) {
        Map<String, List<Long>> idsByKey = new LinkedHashMap<>();
        Map<String, List<String>> visibilitiesByKey = new HashMap<>();
        for (SearchViewer.ViewerScope scope : population.scopes()) {
            boolean team = SearchViewer.TEAM.equals(scope.scopeType());
            if (!team && !SearchViewer.ORGANIZATION.equals(scope.scopeType())) {
                continue;
            }
            if (!(team ? enrolledTeams : enrolledOrgs).contains(scope.scopeId())) {
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
        idsByKey.forEach((key, ids) -> groups.add(new ScopeGroup(
                key.startsWith("T:") ? ActivityScopeType.TEAM : ActivityScopeType.ORGANIZATION,
                List.copyOf(ids), visibilitiesByKey.get(key))));
        return groups;
    }

    /**
     * スコープでのロールが閲覧できる {@link ActivityVisibility} の名前を返す。{@link ActivityVisibilityMapper#toStandard}
     * で共通の可視レベルへ写し、PUBLIC は SUPPORTER 以上、MEMBERS_AND_ABOVE は MEMBER 以上とする（公開経路が無いため
     * PUBLIC にも所属 SUPPORTER 以上を要求する）。それ以外の可視レベルに写る値は誰にも出さない（fail-closed）。
     * ロール不明・SYSTEM_ADMIN・SUPPORTER 未満は空。
     */
    static List<String> visibilitiesFor(String roleName) {
        if (!RolePriority.isRegistered(roleName) || "SYSTEM_ADMIN".equals(roleName)
                || !RolePriority.isAtLeast(roleName, "SUPPORTER")) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (ActivityVisibility v : ActivityVisibility.values()) {
            String required = switch (ActivityVisibilityMapper.toStandard(v)) {
                case PUBLIC -> "SUPPORTER";
                case MEMBERS_AND_ABOVE -> "MEMBER";
                default -> null;
            };
            if (required != null && RolePriority.isAtLeast(roleName, required)) {
                result.add(v.name());
            }
        }
        return List.copyOf(result);
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
