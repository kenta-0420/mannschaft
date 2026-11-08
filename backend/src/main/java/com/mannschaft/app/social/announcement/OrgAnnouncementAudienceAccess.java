package com.mannschaft.app.social.announcement;

import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.dashboard.ViewerRole;
import com.mannschaft.app.dashboard.service.RoleResolver;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.social.announcement.audience.AnnouncementAudienceMatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 組織告知を閲覧者が読める資格の判定（ALIC-1 §2.1。F01.2.1 §8.2）。<b>トランザクションを持たず、{@code @Transactional} の
 * クラス・メソッドからは呼ばない</b>（キャッシュなしの役割解決を tx 内へ持ち込まない。ArchUnit F04 が検出する）。
 *
 * <h2>2 つの資格</h2>
 * <ul>
 *   <li><b>Qd 直属資格</b> — 組織での役割（キャッシュなし）。宛先条件は従来どおり: 宛先の指定がない（全チーム宛て）か、
 *       閲覧者が組織に直属で在籍しているか、閲覧者の現役チームが宛先に含まれていれば通る。可視性は組織役割で判定する。</li>
 *   <li><b>Qa 宛先資格</b> — 閲覧者の現役チームのうち、①組織に ACTIVE 加盟 ②アーカイブ・論理削除でない
 *       ③ {@link AnnouncementAudienceMatcher} で宛先に入る、を満たすチームでの役割の最大。ADMIN / DEPUTY_ADMIN / MEMBER は
 *       MEMBER（上限）、SUPPORTER は SUPPORTER、GUEST・役割なしは資格なし。宛先外チームの役割は数えない。</li>
 * </ul>
 *
 * <p>告知を読める＝（Qd が宛先条件を満たし可視性が Qd に届く）または（Qa があり可視性が Qa に届く）。
 * 拒否は呼び出し側で同一の 404 ANNOUNCE_001 に畳む（存在オラクルを作らない）。</p>
 *
 * <p>SQL 本数: 告知・チームの件数に依らず一定（組織役割・閲覧者のチーム 2・宛先照合 最大 3・チーム役割まとめて）。</p>
 */
@Component
@RequiredArgsConstructor
public class OrgAnnouncementAudienceAccess {

    private final RoleResolver roleResolver;
    private final MembershipService membershipService;
    private final AnnouncementAudienceMatcher audienceMatcher;

    /**
     * 閲覧者 1 人に対する 1 件の組織告知の資格判定結果。
     *
     * @param directRole       Qd（組織での役割。キャッシュなし）
     * @param directAllowed    Qd で読めるか（宛先条件と可視性の両方）
     * @param delegatedRole    Qa（"MEMBER" / "SUPPORTER"。資格なしは null）
     * @param delegatedAllowed Qa で可視性が届くか
     */
    public record Access(ViewerRole directRole, boolean directAllowed, String delegatedRole, boolean delegatedAllowed) {

        /** どちらかの資格で読めるか。 */
        public boolean allowed() {
            return directAllowed || delegatedAllowed;
        }
    }

    /** 1 件の組織告知について閲覧者の資格を判定する。 */
    public Access evaluate(AnnouncementFeedEntity feed, Long viewerId) {
        return evaluateAll(List.of(feed), viewerId).get(feed.getId());
    }

    /**
     * 同じ組織の複数の組織告知について閲覧者の資格をまとめて判定する（read-batch。ALIC-1 §2.7）。
     *
     * @return 告知 ID → 資格（入力の全告知が現れる）
     */
    public Map<Long, Access> evaluateAll(Collection<AnnouncementFeedEntity> feeds, Long viewerId) {
        Map<Long, Access> result = new LinkedHashMap<>();
        if (feeds == null || feeds.isEmpty()) {
            return result;
        }
        List<AnnouncementFeedEntity> orgFeeds = feeds.stream()
                .filter(f -> f.getScopeType() == AnnouncementScopeType.ORGANIZATION).toList();
        // 組織ごとの Qd（キャッシュなし）。read-batch は 1 組織のパス配下なので通常 1 回。
        Map<Long, ViewerRole> directRoles = new LinkedHashMap<>();
        for (AnnouncementFeedEntity feed : orgFeeds) {
            directRoles.computeIfAbsent(feed.getScopeId(),
                    orgId -> roleResolver.resolveViewerRoleForPreview(viewerId, "ORGANIZATION", orgId));
        }
        Set<Long> viewerOrgs = new HashSet<>();
        Set<Long> viewerTeams = new LinkedHashSet<>();
        boolean needsTeams = orgFeeds.stream().anyMatch(f -> !directRoles.get(f.getScopeId()).equals(ViewerRole.SYSTEM_ADMIN));
        if (needsTeams) {
            viewerOrgs.addAll(membershipService.getActiveOrgIdsIncludingRoleAssignments(viewerId));
            viewerTeams.addAll(membershipService.getActiveTeamIdsIncludingRoleAssignments(viewerId));
        }
        // 宛先に含まれる自分のチーム（告知ごと）
        Map<Long, Set<Long>> matched = needsTeams
                ? audienceMatcher.matchingPairs(orgFeeds, viewerTeams) : Map.of();
        // 宛先に含まれるチームの最新の役割をまとめて 1 回で解決する
        Set<Long> matchedTeams = new LinkedHashSet<>();
        matched.values().forEach(matchedTeams::addAll);
        Map<Long, ViewerRole> teamRoles = new LinkedHashMap<>();
        if (!matchedTeams.isEmpty()) {
            Set<ScopeKey> scopes = new LinkedHashSet<>();
            matchedTeams.forEach(teamId -> scopes.add(new ScopeKey("TEAM", teamId)));
            roleResolver.resolveViewerRolesFresh(viewerId, scopes)
                    .forEach((scope, role) -> teamRoles.put(scope.scopeId(), role));
        }

        for (AnnouncementFeedEntity feed : feeds) {
            if (feed.getScopeType() != AnnouncementScopeType.ORGANIZATION) {
                result.put(feed.getId(), new Access(ViewerRole.PUBLIC, false, null, false));
                continue;
            }
            ViewerRole qd = directRoles.get(feed.getScopeId());
            Set<Long> matchedForFeed = matched.getOrDefault(feed.getId(), Set.of());
            boolean audienceOk = qd == ViewerRole.SYSTEM_ADMIN
                    || !AnnouncementAudienceMatcher.isAudienceRestricted(feed)
                    || viewerOrgs.contains(feed.getScopeId())
                    || !matchedForFeed.isEmpty();
            boolean directAllowed = audienceOk && AnnouncementVisibility.isVisibleTo(feed.getVisibility(), qd.name());
            String qa = delegatedRole(matchedForFeed, teamRoles);
            boolean delegatedAllowed = qa != null && AnnouncementVisibility.isVisibleTo(feed.getVisibility(), qa);
            result.put(feed.getId(), new Access(qd, directAllowed, qa, delegatedAllowed));
        }
        return result;
    }

    /** 宛先に含まれるチームでの役割の最大を委任役割に写像する（ADMIN/DEPUTY_ADMIN/MEMBER→MEMBER、SUPPORTER→SUPPORTER、他は資格なし）。 */
    private static String delegatedRole(Set<Long> matchedTeams, Map<Long, ViewerRole> teamRoles) {
        String best = null;
        List<Long> teams = new ArrayList<>(matchedTeams);
        for (Long teamId : teams) {
            ViewerRole role = teamRoles.get(teamId);
            if (role == null) {
                continue;
            }
            switch (role) {
                case SYSTEM_ADMIN, ADMIN, DEPUTY_ADMIN, MEMBER -> best = "MEMBER";
                case SUPPORTER -> {
                    if (best == null) {
                        best = "SUPPORTER";
                    }
                }
                default -> {
                    // PUBLIC（GUEST・役割なし）は資格なし
                }
            }
            if ("MEMBER".equals(best)) {
                break;
            }
        }
        return best;
    }
}
