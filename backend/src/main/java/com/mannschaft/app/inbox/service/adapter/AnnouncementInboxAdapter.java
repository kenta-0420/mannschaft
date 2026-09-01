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
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.social.announcement.AnnouncementFeedEntity;
import com.mannschaft.app.social.announcement.AnnouncementFeedQueryRepository;
import com.mannschaft.app.social.announcement.AnnouncementFeedRepository;
import com.mannschaft.app.social.announcement.AnnouncementReadStatusRepository;
import com.mannschaft.app.social.announcement.AnnouncementScopeType;
import com.mannschaft.app.social.announcement.AnnouncementVisibility;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.payment.spi.ContentGateTarget;
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
 * （組織）で所属を列挙し、{@code RoleResolver.resolveViewerRole} で閲覧者ロールを解決している。
 * 本アダプタはこれを踏襲し、閲覧者ロール → 可視 visibility 集合の変換は
 * {@link AnnouncementVisibility} の正準マッピングに委譲する（写経複製を排除し漏洩を根治）。</p>
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
    private final PaymentGateService paymentGateService;

    @Override
    public InboxSourceType sourceType() {
        return InboxSourceType.ANNOUNCEMENT;
    }

    @Override
    public List<InboxItemDto> fetch(Long userId, int window) {
        if (window <= 0) {
            return List.of();
        }
        // 1. 本人の所属スコープを列挙し、各スコープでの閲覧者ロール名を解決する（DashboardService 踏襲）。
        Map<ScopeKey, String> scopeRole = resolveAccessibleScopes(userId);
        if (scopeRole.isEmpty()) {
            return List.of();
        }

        // 2. 各スコープのお知らせフィードを findByScope で取得する（重複 feed.id は除外）。
        // Phase3 ③：各スコープを window 件まで（無制限 fetch を根絶）。複数スコープの和集合から
        // 集約側が全順序ソートで上位 window 件を選ぶため、スコープ毎に window 件取れば取りこぼさない。
        Map<Long, AnnouncementFeedEntity> feedById = new LinkedHashMap<>();
        for (Map.Entry<ScopeKey, String> e : scopeRole.entrySet()) {
            ScopeKey scope = e.getKey();
            // 閲覧者ロール → 閲覧できる visibility 集合（正準）。
            List<AnnouncementFeedEntity> feeds = announcementFeedQueryRepository.findByScope(
                    scope.scopeType(), scope.scopeId(),
                    AnnouncementVisibility.allowedFor(e.getValue()), null, window);
            for (AnnouncementFeedEntity feed : feeds) {
                feedById.putIfAbsent(feed.getId(), feed);
            }
        }
        if (feedById.isEmpty()) {
            return List.of();
        }

        // 3. 既読状態を user_id でまとめ取り（N+1 回避＝feed 件数に依らず 1 クエリ）。
        List<Long> feedIds = new ArrayList<>(feedById.keySet());
        Map<Long, ContentGateTarget> targets = feedById.values().stream()
                .filter(feed -> AnnouncementInboxAdapter.targetOf(feed) != null)
                .collect(Collectors.toMap(
                        AnnouncementFeedEntity::getId,
                        AnnouncementInboxAdapter::targetOf));
        Map<Long, GateCheckResponse> gateResults = paymentGateService == null ? Map.of()
                : paymentGateService.checkAccessBatch(ContentGateType.ANNOUNCEMENT, feedIds, userId, targets);
        feedById.entrySet().removeIf(e -> {
            GateCheckResponse gate = gateResults == null ? null : gateResults.get(e.getKey());
            return gate == null || gate.isTitleHidden();
        });
        feedIds = new ArrayList<>(feedById.keySet());
        Set<Long> readFeedIds = announcementReadStatusRepository
                .findByUserIdAndAnnouncementFeedIdIn(userId, feedIds).stream()
                .map(r -> r.getAnnouncementFeedId())
                .collect(Collectors.toSet());

        // 4. 統一 DTO へ正規化する。
        List<InboxItemDto> items = new ArrayList<>(feedById.size());
        for (AnnouncementFeedEntity feed : feedById.values()) {
            GateCheckResponse gate = gateResults == null ? null : gateResults.get(feed.getId());
            items.add(toDto(feed, readFeedIds.contains(feed.getId()),
                    gate != null && !gate.isAccessible() && !gate.isTitleHidden()));
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
        Map<ScopeKey, String> scopeRole = resolveAccessibleScopes(userId);
        ScopeKey key = new ScopeKey(feed.getScopeType(), feed.getScopeId());
        if (!scopeRole.containsKey(key)) {
            // 未所属スコープ（IDOR）。
            return false;
        }
        String viewerRoleName = scopeRole.get(key);
        if (!AnnouncementVisibility.isVisibleTo(feed.getVisibility(), viewerRoleName)) {
            return false;
        }
        if ("ADMIN".equals(viewerRoleName) || "SYSTEM_ADMIN".equals(viewerRoleName)) {
            return true;
        }
        GateCheckResponse gate = paymentGateService == null ? null : paymentGateService.checkAccess(
                ContentGateType.ANNOUNCEMENT, feed.getId(), userId, targetOf(feed));
        return gate != null && (gate.isAccessible() || !gate.isTitleHidden());
    }

    // ─────────────────────────────────────────────────────────────────
    // スコープ解決（DashboardService 踏襲）
    // ─────────────────────────────────────────────────────────────────

    /**
     * 本人の所属スコープ（TEAM/ORGANIZATION）を列挙し、各スコープでの閲覧者ロール名を解決する。
     *
     * <p>値は {@link ViewerRole#name()}（SYSTEM_ADMIN/ADMIN/DEPUTY_ADMIN/MEMBER/SUPPORTER/PUBLIC）。
     * 可視性集合への変換は {@link AnnouncementVisibility#allowedFor(String)} に集約しているため、
     * 本メソッドは「どのスコープを、どのロールで見ているか」だけを返す（写経複製の排除）。</p>
     *
     * <p>{@code RoleResolver.resolveViewerRole} の引数 scopeType は {@code "TEAM"}/{@code "ORGANIZATION"}
     * 文字列を取り、{@link AnnouncementScopeType} とは別物である点に注意。</p>
     */
    private static ContentGateTarget targetOf(AnnouncementFeedEntity feed) {
        if (feed == null || feed.getId() == null || feed.getScopeType() == null || feed.getScopeId() == null) {
            return null;
        }
        return feed.getScopeType() == AnnouncementScopeType.TEAM
                ? new ContentGateTarget(feed.getId(), feed.getScopeId(), null)
                : feed.getScopeType() == AnnouncementScopeType.ORGANIZATION
                    ? new ContentGateTarget(feed.getId(), null, feed.getScopeId()) : null;
    }

    private Map<ScopeKey, String> resolveAccessibleScopes(Long userId) {
        Map<ScopeKey, String> result = new LinkedHashMap<>();

        // チームスコープ（CMP-027: user_roles ∪ memberships の在籍チーム）
        for (Long teamId : userRoleRepository.findTeamIdsByUserId(userId)) {
            ScopeKey key = new ScopeKey(AnnouncementScopeType.TEAM, teamId);
            if (result.containsKey(key)) {
                continue;
            }
            ViewerRole viewerRole = roleResolver.resolveViewerRole(userId, "TEAM", teamId);
            result.put(key, viewerRole == null ? null : viewerRole.name());
        }

        // 組織スコープ（CMP-027: user_roles ∪ memberships の在籍組織）
        for (Long orgId : userRoleRepository.findOrganizationIdsByUserId(userId)) {
            ScopeKey key = new ScopeKey(AnnouncementScopeType.ORGANIZATION, orgId);
            if (result.containsKey(key)) {
                continue;
            }
            ViewerRole viewerRole = roleResolver.resolveViewerRole(userId, "ORGANIZATION", orgId);
            result.put(key, viewerRole == null ? null : viewerRole.name());
        }
        return result;
    }

    private InboxItemDto toDto(AnnouncementFeedEntity feed, boolean read, boolean locked) {
        InboxPriority priority = priorityNormalizer.normalize(
                InboxSourceType.ANNOUNCEMENT, feed.getPriority());

        InboxItemDto.ScopeDto scope = new InboxItemDto.ScopeDto(
                feed.getScopeType() != null ? feed.getScopeType().name() : null,
                feed.getScopeId(),
                null);

        // 名寄せ（Phase 3 ①）：feed は終端 sourceType + sourceId を保持するので正規化できる。
        // 正規化不能（ReferenceType 未マッピングの ADVERTISER_CAMPAIGN 等）は ANNOUNCEMENT_FEED:{feedId}
        // へフォールバックし畳まない（NOTIFICATION 側の "ANNOUNCEMENT:{id}" 自分自身キーとも衝突しない）。
        String terminalType = locked || feed.getSourceType() == null ? null : feed.getSourceType().name();
        String selfKey = "ANNOUNCEMENT_FEED:" + feed.getId();
        String canonicalRef = locked ? selfKey : dedupeKeyResolver.canonicalRefOrSelf(
                terminalType, feed.getSourceId(), selfKey);

        return new InboxItemDto(
                InboxSourceType.ANNOUNCEMENT.name() + ":" + feed.getId(),
                InboxSourceType.ANNOUNCEMENT,
                feed.getId(),
                feed.getTitleCache(),
                locked ? null : feed.getExcerptCache(),
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
