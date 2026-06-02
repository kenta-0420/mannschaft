package com.mannschaft.app.inbox.service.adapter;

import com.mannschaft.app.dashboard.ViewerRole;
import com.mannschaft.app.dashboard.service.RoleResolver;
import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.InboxState;
import com.mannschaft.app.inbox.dto.InboxItemDto;
import com.mannschaft.app.inbox.dto.InboxItemRef;
import com.mannschaft.app.inbox.service.InboxDedupeKeyResolver;
import com.mannschaft.app.inbox.service.InboxPriorityNormalizer;
import com.mannschaft.app.inbox.service.InboxSourceAdapter;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.social.announcement.AnnouncementFeedEntity;
import com.mannschaft.app.social.announcement.AnnouncementFeedQueryRepository;
import com.mannschaft.app.social.announcement.AnnouncementFeedRepository;
import com.mannschaft.app.social.announcement.AnnouncementReadStatusRepository;
import com.mannschaft.app.social.announcement.AnnouncementScopeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * F04.11 統合通知インボックス：ANNOUNCEMENT ソースアダプタ（F02.6/F02.8 announcement_feeds）。
 *
 * <p>本人の所属スコープ（チーム/組織）の有効なお知らせフィードを {@code AnnouncementFeedQueryRepository.findByScope}
 * で取得し（{@code DashboardService.getTeamDashboard}/{@code getOrgDashboard} と同じ経路・同じ visibility 解決を踏襲）、
 * {@code announcement_read_status} の既読有無を被せて統一 DTO へ正規化する（読み取りのみ・書き込み越境なし＝CLAUDE.md 原則5）。
 * priority は {@code titleCache} の元コンテンツ優先度（URGENT/IMPORTANT/NORMAL）を写像する。
 * 設計書: 03_business_logic.md §2・01_data_model.md §3.2・04_security_operations.md §1.2。</p>
 *
 * <p><b>スコープ解決の踏襲元</b>: {@code DashboardService} は
 * {@code userRoleRepository.findByUserIdAndTeamIdIsNotNull}（チーム）/{@code findByUserIdAndOrganizationIdIsNotNull}
 * （組織）で所属を列挙し、{@code RoleResolver.resolveViewerRole} → {@code resolveVisibilityParam} で
 * visibility 文字列を解決している。本アダプタはこれを忠実に再現する（症状隠蔽・握り潰しなし）。</p>
 */
@Component
@RequiredArgsConstructor
public class AnnouncementInboxAdapter implements InboxSourceAdapter {

    private final UserRoleRepository userRoleRepository;
    private final RoleResolver roleResolver;
    private final AnnouncementFeedQueryRepository announcementFeedQueryRepository;
    private final AnnouncementFeedRepository announcementFeedRepository;
    private final AnnouncementReadStatusRepository announcementReadStatusRepository;
    private final InboxPriorityNormalizer priorityNormalizer;
    private final InboxDedupeKeyResolver dedupeKeyResolver;

    @Override
    public InboxSourceType sourceType() {
        return InboxSourceType.ANNOUNCEMENT;
    }

    @Override
    public List<InboxItemDto> fetch(Long userId, int window) {
        if (window <= 0) {
            return List.of();
        }
        // 1. 本人の所属スコープを列挙し、各スコープの visibility パラメータを解決する（DashboardService 踏襲）。
        Map<ScopeKey, String> scopeVisibility = resolveAccessibleScopes(userId);
        if (scopeVisibility.isEmpty()) {
            return List.of();
        }

        // 2. 各スコープのお知らせフィードを findByScope で取得する（重複 feed.id は除外）。
        // Phase3 ③：各スコープを window 件まで（無制限 fetch を根絶）。複数スコープの和集合から
        // 集約側が全順序ソートで上位 window 件を選ぶため、スコープ毎に window 件取れば取りこぼさない。
        Map<Long, AnnouncementFeedEntity> feedById = new LinkedHashMap<>();
        for (Map.Entry<ScopeKey, String> e : scopeVisibility.entrySet()) {
            ScopeKey scope = e.getKey();
            List<AnnouncementFeedEntity> feeds = announcementFeedQueryRepository.findByScope(
                    scope.scopeType(), scope.scopeId(), e.getValue(), null, window);
            for (AnnouncementFeedEntity feed : feeds) {
                feedById.putIfAbsent(feed.getId(), feed);
            }
        }
        if (feedById.isEmpty()) {
            return List.of();
        }

        // 3. 既読状態を user_id でまとめ取り（N+1 回避＝feed 件数に依らず 1 クエリ）。
        List<Long> feedIds = new ArrayList<>(feedById.keySet());
        Set<Long> readFeedIds = announcementReadStatusRepository
                .findByUserIdAndAnnouncementFeedIdIn(userId, feedIds).stream()
                .map(r -> r.getAnnouncementFeedId())
                .collect(Collectors.toSet());

        // 4. 統一 DTO へ正規化する。
        List<InboxItemDto> items = new ArrayList<>(feedById.size());
        for (AnnouncementFeedEntity feed : feedById.values()) {
            items.add(toDto(feed, readFeedIds.contains(feed.getId())));
        }
        return items;
    }

    @Override
    public boolean isVisibleTo(Long userId, Long sourceId) {
        AnnouncementFeedEntity feed = announcementFeedRepository.findById(sourceId).orElse(null);
        if (feed == null) {
            return false;
        }
        // 一覧取得と同じ可視性ロジックの再利用: 失効・削除済みは不可視。
        if (feed.getSourceDeletedAt() != null) {
            return false;
        }
        if (feed.getExpiresAt() != null && !feed.getExpiresAt().isAfter(LocalDateTime.now())) {
            return false;
        }
        // 当該 feed のスコープがユーザーの所属スコープに含まれ、かつ role の visibility 範囲に収まるか。
        Map<ScopeKey, String> scopeVisibility = resolveAccessibleScopes(userId);
        String visibilityParam = scopeVisibility.get(
                new ScopeKey(feed.getScopeType(), feed.getScopeId()));
        if (visibilityParam == null) {
            return false;
        }
        return isVisibleByRole(feed.getVisibility(), visibilityParam);
    }

    // ─────────────────────────────────────────────────────────────────
    // スコープ解決（DashboardService 踏襲）
    // ─────────────────────────────────────────────────────────────────

    /**
     * 本人の所属スコープ（TEAM/ORGANIZATION）を列挙し、各スコープでの visibility パラメータを解決する。
     *
     * <p>{@code RoleResolver.resolveViewerRole} の引数 scopeType は {@code "TEAM"}/{@code "ORGANIZATION"}
     * 文字列を取り、{@link AnnouncementScopeType} とは別物である点に注意。</p>
     */
    private Map<ScopeKey, String> resolveAccessibleScopes(Long userId) {
        Map<ScopeKey, String> result = new LinkedHashMap<>();

        // チームスコープ
        for (UserRoleEntity role : userRoleRepository.findByUserIdAndTeamIdIsNotNull(userId)) {
            Long teamId = role.getTeamId();
            if (teamId == null) {
                continue;
            }
            ScopeKey key = new ScopeKey(AnnouncementScopeType.TEAM, teamId);
            if (result.containsKey(key)) {
                continue;
            }
            ViewerRole viewerRole = roleResolver.resolveViewerRole(userId, "TEAM", teamId);
            result.put(key, resolveVisibilityParam(viewerRole));
        }

        // 組織スコープ
        for (UserRoleEntity role : userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(userId)) {
            Long orgId = role.getOrganizationId();
            if (orgId == null) {
                continue;
            }
            ScopeKey key = new ScopeKey(AnnouncementScopeType.ORGANIZATION, orgId);
            if (result.containsKey(key)) {
                continue;
            }
            ViewerRole viewerRole = roleResolver.resolveViewerRole(userId, "ORGANIZATION", orgId);
            result.put(key, resolveVisibilityParam(viewerRole));
        }
        return result;
    }

    /**
     * ViewerRole から {@code AnnouncementFeedQueryRepository.findByScope} の visibility パラメータ文字列を
     * 解決する（{@code DashboardService.resolveVisibilityParam} を忠実に踏襲）。
     */
    private String resolveVisibilityParam(ViewerRole viewerRole) {
        if (viewerRole == null) {
            return null;
        }
        return switch (viewerRole) {
            case MEMBER, ADMIN, DEPUTY_ADMIN, SYSTEM_ADMIN -> "MEMBERS_ONLY";
            case SUPPORTER -> "SUPPORTERS_AND_ABOVE";
            default -> null;
        };
    }

    /**
     * {@code findByScope} の WHERE 句と同じ可視性述語を、単一 feed に対して評価する（isVisibleTo 用）。
     *
     * <ul>
     *   <li>{@code "MEMBERS_ONLY"} — visibility = MEMBERS_ONLY のみ</li>
     *   <li>{@code "SUPPORTERS_AND_ABOVE"} — visibility IN (MEMBERS_ONLY, SUPPORTERS_AND_ABOVE)</li>
     *   <li>それ以外（PUBLIC 等）— フィルタなし（全件可視）</li>
     * </ul>
     */
    private boolean isVisibleByRole(String feedVisibility, String visibilityParam) {
        if ("MEMBERS_ONLY".equals(visibilityParam)) {
            return "MEMBERS_ONLY".equals(feedVisibility);
        }
        if ("SUPPORTERS_AND_ABOVE".equals(visibilityParam)) {
            return "MEMBERS_ONLY".equals(feedVisibility)
                    || "SUPPORTERS_AND_ABOVE".equals(feedVisibility);
        }
        // PUBLIC 等はフィルタなし
        return true;
    }

    private InboxItemDto toDto(AnnouncementFeedEntity feed, boolean read) {
        InboxPriority priority = priorityNormalizer.normalize(
                InboxSourceType.ANNOUNCEMENT, feed.getPriority());

        InboxItemDto.ScopeDto scope = new InboxItemDto.ScopeDto(
                feed.getScopeType() != null ? feed.getScopeType().name() : null,
                feed.getScopeId(),
                null);

        // 名寄せ（Phase 3 ①）：feed は終端 sourceType + sourceId を保持するので正規化できる。
        // 正規化不能（ReferenceType 未マッピングの ADVERTISER_CAMPAIGN 等）は ANNOUNCEMENT_FEED:{feedId}
        // へフォールバックし畳まない（NOTIFICATION 側の "ANNOUNCEMENT:{id}" 自分自身キーとも衝突しない）。
        String terminalType = feed.getSourceType() != null ? feed.getSourceType().name() : null;
        String selfKey = "ANNOUNCEMENT_FEED:" + feed.getId();
        String canonicalRef = dedupeKeyResolver.canonicalRefOrSelf(
                terminalType, feed.getSourceId(), selfKey);

        return new InboxItemDto(
                InboxSourceType.ANNOUNCEMENT.name() + ":" + feed.getId(),
                InboxSourceType.ANNOUNCEMENT,
                feed.getId(),
                feed.getTitleCache(),
                feed.getExcerptCache(),
                priority,
                scope,
                "/announcements/" + feed.getId(),
                feed.getCreatedAt(),
                read ? InboxState.READ : InboxState.UNREAD,
                null,
                List.of(),
                canonicalRef,
                1,
                List.of(new InboxItemRef(InboxSourceType.ANNOUNCEMENT, feed.getId())));
    }

    /** スコープ識別キー（announcement_feeds の scope_type + scope_id）。 */
    private record ScopeKey(AnnouncementScopeType scopeType, Long scopeId) {
    }
}
