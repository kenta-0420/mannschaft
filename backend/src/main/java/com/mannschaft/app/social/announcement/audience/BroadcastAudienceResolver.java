package com.mannschaft.app.social.announcement.audience;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.service.MembershipStatsQueryService;
import com.mannschaft.app.organization.teamgroup.service.OrgTeamGroupService;
import com.mannschaft.app.organization.teamgroup.service.OrgTeamGroupService.TeamGroupCatalog;
import com.mannschaft.app.organization.teamgroup.service.OrgTeamGroupView;
import com.mannschaft.app.social.announcement.AnnouncementChannel;
import com.mannschaft.app.social.announcement.AnnouncementErrorCode;
import com.mannschaft.app.social.announcement.AnnouncementRangeTemplateEntity;
import com.mannschaft.app.social.announcement.AnnouncementRangeTemplateRepository;
import com.mannschaft.app.social.announcement.AnnouncementScopeType;
import com.mannschaft.app.social.announcement.AnnouncementVisibility;
import com.mannschaft.app.social.announcement.dto.AudiencePreviewRequestDto;
import com.mannschaft.app.social.announcement.dto.AudiencePreviewResponseDto;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService.ActiveTeamGroupAssignment;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 告知の宛先を解決・検証する（F01.2.1 §8.1〜§8.4。BROADCAST_002・006〜012）。
 *
 * <p><b>トランザクションを持たない</b>。宛先の解決は organization（グループ）・team（加盟）・role（メンバー数）の
 * 各ドメインの公開 Service を呼ぶため、告知の {@code @Transactional}（social ドメイン）の外で行い、
 * 解決結果 {@link ResolvedBroadcastAudience} だけを告知のトランザクションへ渡す（CLAUDE.md 原則 5）。</p>
 *
 * <p>どの経路でも、先頭で {@link AccessControlService#checkMembership} を呼ぶ（非メンバー・他組織・存在しない
 * スコープは 403 {@code COMMON_002}）。宛先の検証（他組織のグループ ID は 400 {@code BROADCAST_006} など）を
 * 認可より先に行うと、非メンバーに他組織のグループやチームの存在が漏れるため、順序を固定する（AC-G122）。</p>
 *
 * <p>候補は「組織に ACTIVE で加盟しているチーム」だけである（PENDING・離脱済み・他組織は含めない。AC-K07）。
 * 「チームを選ぶ」の検証は {@link TeamOrgMembershipQueryService#findActiveTeamIdsIn}、グループの展開は
 * {@link TeamOrgMembershipQueryService#findActiveTeamGroupAssignments} で行う。</p>
 */
@Component
@RequiredArgsConstructor
public class BroadcastAudienceResolver {

    /** 「チームを選ぶ」の上限（マスター裁定 2026-09-30。多値インデックスの実測 669 件に収まる値）。 */
    public static final int MAX_TARGET_TEAMS = 500;

    /** 個別チェックできるグループ数の上限（グループの作成上限と同じ 1 組織 100 件）。 */
    public static final int MAX_TARGET_GROUPS = 100;

    /** プレビューで返すチームのサンプル数。 */
    static final int SAMPLE_SIZE = 50;

    /** 宛先を絞った告知で push を出せる DEPUTY_ADMIN の権限（§8.5.1・M2）。 */
    static final String PUSH_PERMISSION = "MANAGE_CONTENT";

    /** テンプレートのグループを除外したときの警告コード（{@code TEMPLATE_GROUPS_REMOVED:N}。§8.6）。 */
    public static final String WARNING_TEMPLATE_GROUPS_REMOVED = "TEMPLATE_GROUPS_REMOVED";

    private static final String ORGANIZATION = "ORGANIZATION";
    private static final String TEAM = "TEAM";

    private final AccessControlService accessControlService;
    private final TeamOrgMembershipQueryService membershipQueryService;
    private final OrgTeamGroupService orgTeamGroupService;
    private final TeamService teamService;
    private final MembershipStatsQueryService membershipStatsQueryService;
    private final AnnouncementRangeTemplateRepository templateRepository;

    /**
     * broadcast 用に宛先を解決する。グループ指定で宛先チームも直属メンバーも 0 なら 400 {@code BROADCAST_009}。
     *
     * @param callerUserId 送信者
     * @param scopeType    {@code TEAM} / {@code ORGANIZATION}
     * @param scopeId      チーム ID または組織 ID
     * @param spec         宛先指定
     * @return 解決済みの宛先（TEAM スコープ・絞り込みなしは {@link ResolvedBroadcastAudience.Mode#ALL}）
     */
    public ResolvedBroadcastAudience resolveForBroadcast(
            Long callerUserId, String scopeType, Long scopeId, BroadcastAudienceSpec spec) {
        accessControlService.checkMembership(callerUserId, scopeId, scopeType);
        ResolvedBroadcastAudience resolved = resolve(callerUserId, scopeType, scopeId, spec);
        if (resolved.mode() == ResolvedBroadcastAudience.Mode.GROUPS
                && resolved.resolvedTeamIds().isEmpty()
                && resolved.directMemberCount() == 0) {
            throw new BusinessException(AnnouncementErrorCode.BROADCAST_009);
        }
        return resolved;
    }

    /**
     * 宛先プレビュー（§8.4・§10.9）。検証は broadcast と同じだが、0 件は 400 にせず 0 を返す
     * （画面が「次へ」を止めるため）。何も書き込まない。
     *
     * @param callerUserId   呼び出したユーザー
     * @param organizationId 組織 ID
     * @param request        宛先指定と channel・targetRole
     * @return 件数・サンプル・展開後のグループ・push の可否
     */
    public AudiencePreviewResponseDto preview(Long callerUserId, Long organizationId, AudiencePreviewRequestDto request) {
        accessControlService.checkMembership(callerUserId, organizationId, ORGANIZATION);
        ResolvedBroadcastAudience resolved = resolve(callerUserId, ORGANIZATION, organizationId, request.toSpec());

        List<Long> teamIds = resolved.mode() == ResolvedBroadcastAudience.Mode.ALL
                ? membershipQueryService.findActiveTeamGroupAssignments(organizationId).stream()
                        .map(ActiveTeamGroupAssignment::teamId).toList()
                : resolved.resolvedTeamIds();

        List<AudiencePreviewResponseDto.GroupItem> groups = resolved.groups().stream()
                .map(g -> new AudiencePreviewResponseDto.GroupItem(g.id(), g.name()))
                .toList();

        return new AudiencePreviewResponseDto(
                teamIds.size(),
                resolved.directMemberCount(),
                sampleTeams(teamIds),
                groups,
                pushEnabled(callerUserId, organizationId, request.getChannel()),
                resolved.warnings());
    }

    /**
     * 範囲テンプレートに保存するグループ項目を検証する（F01.2.1 §8.6 の保存側。何も書き込まない）。
     *
     * <p>呼び出す前に、保存できる人かの認可（{@code AnnouncementRangeTemplateAuthorizer#checkWritable}）を済ませること
     * （他組織のグループ ID の存在を権限のない人に漏らさないため）。個別・範囲の端は、その組織の生存グループで
     * なければ 400 {@code BROADCAST_006}、両端 null や向きの逆転は 400 {@code BROADCAST_008}、
     * グループ機能が無効なら 400 {@code BROADCAST_007}。</p>
     *
     * @param organizationId    組織 ID
     * @param targetGroupIds    個別グループ（null 可）
     * @param range             範囲（null 可）
     * @param includeUnassigned 未分類を含めるか（null 可）
     */
    public void validateTemplateGroupItems(
            Long organizationId, List<UUID> targetGroupIds, TargetGroupRange range, Boolean includeUnassigned) {
        boolean any = (targetGroupIds != null && !targetGroupIds.isEmpty())
                || range != null || Boolean.TRUE.equals(includeUnassigned);
        if (!any) {
            return;
        }
        TeamGroupCatalog catalog = orgTeamGroupService.findAudienceCatalog(organizationId);
        if (!catalog.enabled()) {
            throw new BusinessException(AnnouncementErrorCode.BROADCAST_007);
        }
        Map<UUID, Integer> indexOf = new HashMap<>();
        List<OrgTeamGroupView> live = catalog.liveGroups();
        for (int i = 0; i < live.size(); i++) {
            indexOf.put(live.get(i).id(), i);
        }
        if (targetGroupIds != null) {
            if (targetGroupIds.size() > MAX_TARGET_GROUPS) {
                throw new BusinessException(AnnouncementErrorCode.BROADCAST_006);
            }
            for (UUID id : targetGroupIds) {
                if (id == null || !indexOf.containsKey(id)) {
                    throw new BusinessException(AnnouncementErrorCode.BROADCAST_006);
                }
            }
        }
        if (range != null) {
            if (range.fromGroupId() == null && range.toGroupId() == null) {
                throw new BusinessException(AnnouncementErrorCode.BROADCAST_008);
            }
            int fromIndex = range.fromGroupId() == null ? 0 : requireIndex(indexOf, range.fromGroupId());
            int toIndex = range.toGroupId() == null ? live.size() - 1 : requireIndex(indexOf, range.toGroupId());
            if (fromIndex > toIndex) {
                throw new BusinessException(AnnouncementErrorCode.BROADCAST_008);
            }
        }
    }

    // ───────── 解決 ─────────

    private ResolvedBroadcastAudience resolve(
            Long callerUserId, String scopeType, Long scopeId, BroadcastAudienceSpec spec) {
        BroadcastAudienceSpec s = spec != null ? spec : new BroadcastAudienceSpec(null, null, null, null, null, null);
        if (TEAM.equals(scopeType)) {
            if (s.hasGroupItems()) {
                throw new BusinessException(AnnouncementErrorCode.BROADCAST_012);
            }
            return ResolvedBroadcastAudience.unrestricted();
        }
        if (!ORGANIZATION.equals(scopeType)) {
            return ResolvedBroadcastAudience.unrestricted();
        }
        if (s.templateId() != null) {
            // §8.6 手順1: (id, scope) で取得。他スコープ・不在は区別せず 400 BROADCAST_003
            AnnouncementRangeTemplateEntity template = templateRepository.findById(s.templateId())
                    .filter(t -> t.getScopeType() == AnnouncementScopeType.ORGANIZATION
                            && t.getScopeId().equals(scopeId))
                    .orElseThrow(() -> new BusinessException(AnnouncementErrorCode.BROADCAST_003));
            if (s.isTemplateOnly()) {
                // 手順2: 宛先を明示していないときだけ、テンプレートの宛先を使う（明示があれば明示が優先）
                return resolveTemplate(callerUserId, scopeId, s, template);
            }
        }
        if (s.hasTeamItems() && s.hasGroupItems()) {
            throw new BusinessException(AnnouncementErrorCode.BROADCAST_011);
        }
        boolean includeSupporters = includesSupporters(s.targetRole());
        if (s.hasTeamItems()) {
            return resolveTeams(callerUserId, scopeId, s.targetTeamIds(), includeSupporters);
        }
        if (s.hasGroupItems()) {
            return resolveGroups(callerUserId, scopeId, s, includeSupporters);
        }
        return ResolvedBroadcastAudience.unrestricted();
    }

    /**
     * テンプレートの宛先をサーバー側で解決する（§8.6 手順3〜5）。
     *
     * <p>範囲は保存した ID のまま、送信時点の並び順（sort_order）で展開する（AC-J01）。範囲の端が削除済みなら
     * 400 {@code BROADCAST_013}（AC-J03。個別選択の除外より優先）。個別選択の削除済み・他組織の ID は黙って除外し、
     * 警告 {@code TEMPLATE_GROUPS_REMOVED:N} を載せる（AC-J02）。グループ項目の無いテンプレートは、
     * 保存済みの {@code target_team_ids}（無ければ「すべてのチーム」）をそのまま使う。</p>
     */
    private ResolvedBroadcastAudience resolveTemplate(
            Long callerUserId, Long organizationId, BroadcastAudienceSpec spec,
            AnnouncementRangeTemplateEntity template) {
        boolean includeSupporters = includesSupporters(spec.targetRole());
        List<UUID> storedIds = TemplateGroupItemsCodec.parseGroupIds(template.getTargetGroupIds());
        TargetGroupRange storedRange = TemplateGroupItemsCodec.parseRange(template.getTargetGroupRange());
        boolean storedUnassigned = Boolean.TRUE.equals(template.getIncludeUnassigned());

        if (storedIds == null && storedRange == null && !storedUnassigned) {
            List<Long> teams = TemplateGroupItemsCodec.parseTeamIds(template.getTargetTeamIds());
            if (teams == null || teams.isEmpty()) {
                return ResolvedBroadcastAudience.unrestricted();
            }
            return resolveTeams(callerUserId, organizationId, teams, includeSupporters);
        }

        TeamGroupCatalog catalog = orgTeamGroupService.findAudienceCatalog(organizationId);
        if (!catalog.enabled()) {
            throw new BusinessException(AnnouncementErrorCode.BROADCAST_007);
        }
        Set<UUID> live = new HashSet<>();
        catalog.liveGroups().forEach(g -> live.add(g.id()));

        if (storedRange != null) {
            UUID from = storedRange.fromGroupId();
            UUID to = storedRange.toGroupId();
            if ((from != null && !live.contains(from)) || (to != null && !live.contains(to))) {
                throw new BusinessException(AnnouncementErrorCode.BROADCAST_013);
            }
        }

        List<UUID> kept = null;
        List<String> warnings = new ArrayList<>();
        if (storedIds != null) {
            List<UUID> distinct = List.copyOf(new LinkedHashSet<>(storedIds));
            kept = distinct.stream().filter(live::contains).toList();
            int removed = distinct.size() - kept.size();
            if (removed > 0) {
                warnings.add(WARNING_TEMPLATE_GROUPS_REMOVED + ":" + removed);
            }
        }
        BroadcastAudienceSpec resolvedSpec = new BroadcastAudienceSpec(
                null, kept, storedRange, storedUnassigned, spec.templateId(), spec.targetRole());
        return resolveGroups(callerUserId, organizationId, resolvedSpec, includeSupporters).withWarnings(warnings);
    }

    /** MEMBERS_AND_ABOVE（と未指定）は純 SUPPORTER を含めない。SUPPORTERS_AND_ABOVE・PUBLIC は含める（§8.5.2）。 */
    private static boolean includesSupporters(String targetRole) {
        return targetRole != null && !AnnouncementVisibility.MEMBERS_AND_ABOVE.equals(targetRole);
    }

    /** 「チームを選ぶ」: 空配列の拒否 → 上限 → 重複排除 → ACTIVE 加盟の照合（1 件でも外れれば全体を拒否）。 */
    private ResolvedBroadcastAudience resolveTeams(
            Long callerUserId, Long organizationId, List<Long> requested, boolean includeSupporters) {
        if (requested.isEmpty()) {
            // 明示の空配列は「誰も選ばなかった」。すべてのチームに倒さない
            throw new BusinessException(AnnouncementErrorCode.BROADCAST_009);
        }
        if (requested.size() > MAX_TARGET_TEAMS) {
            throw new BusinessException(AnnouncementErrorCode.BROADCAST_010);
        }
        if (requested.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(AnnouncementErrorCode.BROADCAST_002);
        }
        List<Long> distinct = List.copyOf(new LinkedHashSet<>(requested));
        Set<Long> active = new HashSet<>(membershipQueryService.findActiveTeamIdsIn(organizationId, distinct));
        if (!active.containsAll(distinct)) {
            throw new BusinessException(AnnouncementErrorCode.BROADCAST_002);
        }
        int direct = countDirectMembers(callerUserId, organizationId, includeSupporters);
        TargetAudience audience = new TargetAudience(
                TargetAudience.MODE_TEAMS, List.of(), null, false, distinct.size(), direct);
        return new ResolvedBroadcastAudience(ResolvedBroadcastAudience.Mode.TEAMS, distinct, List.of(), false,
                Map.of(), distinct, direct, audience, List.of());
    }

    /** 「チームグループで選ぶ」: 機能の有効確認 → 個別・範囲の検証と展開 → ACTIVE 加盟から宛先チームを引く。 */
    private ResolvedBroadcastAudience resolveGroups(
            Long callerUserId, Long organizationId, BroadcastAudienceSpec spec, boolean includeSupporters) {
        TeamGroupCatalog catalog = orgTeamGroupService.findAudienceCatalog(organizationId);
        if (!catalog.enabled()) {
            throw new BusinessException(AnnouncementErrorCode.BROADCAST_007);
        }
        boolean nothingChosen = spec.targetGroupIds() != null && spec.targetGroupIds().isEmpty()
                && spec.targetGroupRange() == null && !Boolean.TRUE.equals(spec.includeUnassigned());
        if (nothingChosen) {
            // 個別の空配列だけ（範囲も未分類も無い）は「誰も選ばなかった」。すべてのチームに倒さない
            throw new BusinessException(AnnouncementErrorCode.BROADCAST_009);
        }
        List<OrgTeamGroupView> live = catalog.liveGroups();
        Map<UUID, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < live.size(); i++) {
            indexOf.put(live.get(i).id(), i);
        }

        Set<UUID> selected = new HashSet<>();
        List<UUID> individual = spec.targetGroupIds();
        if (individual != null && !individual.isEmpty()) {
            if (individual.size() > MAX_TARGET_GROUPS) {
                throw new BusinessException(AnnouncementErrorCode.BROADCAST_006);
            }
            for (UUID id : individual) {
                // 他組織・削除済み・存在しない ID を区別しない（同じ 400 にする）
                if (id == null || !indexOf.containsKey(id)) {
                    throw new BusinessException(AnnouncementErrorCode.BROADCAST_006);
                }
                selected.add(id);
            }
        }

        TargetAudience.RangeRef rangeRef = null;
        TargetGroupRange range = spec.targetGroupRange();
        if (range != null) {
            UUID from = range.fromGroupId();
            UUID to = range.toGroupId();
            if (from == null && to == null) {
                throw new BusinessException(AnnouncementErrorCode.BROADCAST_008);
            }
            int fromIndex = from == null ? 0 : requireIndex(indexOf, from);
            int toIndex = to == null ? live.size() - 1 : requireIndex(indexOf, to);
            if (fromIndex > toIndex) {
                throw new BusinessException(AnnouncementErrorCode.BROADCAST_008);
            }
            for (int i = fromIndex; i <= toIndex; i++) {
                selected.add(live.get(i).id());
            }
            rangeRef = new TargetAudience.RangeRef(
                    from, from == null ? null : live.get(fromIndex).name(),
                    to, to == null ? null : live.get(toIndex).name());
        }

        boolean includeUnassigned = Boolean.TRUE.equals(spec.includeUnassigned());
        List<TargetAudience.GroupRef> groups = new ArrayList<>();
        Map<UUID, List<Long>> groupTeams = new LinkedHashMap<>();
        for (OrgTeamGroupView g : live) {
            if (selected.contains(g.id())) {
                groups.add(new TargetAudience.GroupRef(g.id(), g.name()));
                groupTeams.put(g.id(), new ArrayList<>());
            }
        }

        List<Long> unassigned = new ArrayList<>();
        for (ActiveTeamGroupAssignment a : membershipQueryService.findActiveTeamGroupAssignments(organizationId)) {
            UUID groupId = a.groupId();
            if (groupId != null && groupTeams.containsKey(groupId)) {
                groupTeams.get(groupId).add(a.teamId());
            } else if (groupId == null || !indexOf.containsKey(groupId)) {
                // 削除済みグループを指す行は未分類として扱う（リスナーの付け替えは非同期。§5.1）
                unassigned.add(a.teamId());
            }
        }

        LinkedHashSet<Long> resolvedTeams = new LinkedHashSet<>();
        groupTeams.values().forEach(resolvedTeams::addAll);
        if (includeUnassigned) {
            resolvedTeams.addAll(unassigned);
        }

        int direct = countDirectMembers(callerUserId, organizationId, includeSupporters);
        TargetAudience audience = new TargetAudience(TargetAudience.MODE_GROUPS, List.copyOf(groups), rangeRef,
                includeUnassigned, resolvedTeams.size(), direct);
        Map<UUID, List<Long>> frozen = new LinkedHashMap<>();
        groupTeams.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        return new ResolvedBroadcastAudience(ResolvedBroadcastAudience.Mode.GROUPS, List.of(), List.copyOf(groups),
                includeUnassigned, frozen, List.copyOf(resolvedTeams), direct, audience, List.of());
    }

    private static int requireIndex(Map<UUID, Integer> indexOf, UUID groupId) {
        Integer index = indexOf.get(groupId);
        if (index == null) {
            throw new BusinessException(AnnouncementErrorCode.BROADCAST_006);
        }
        return index;
    }

    /**
     * 直属メンバー数。組織スコープの ACTIVE メンバーから送信者本人を除いた人数とする
     * （送信者は必ず組織メンバーなので、本人を数えると「対象になる人がいない」が成立しなくなる。§8.3）。
     * 告知対象ロールが MEMBERS_AND_ABOVE なら純 SUPPORTER を数えない（告知が見えない人を数えない）。
     */
    private int countDirectMembers(Long callerUserId, Long organizationId, boolean includeSupporters) {
        long members = membershipStatsQueryService.countActiveMembersExcluding(
                ScopeType.ORGANIZATION, organizationId, includeSupporters, callerUserId);
        return Math.toIntExact(members);
    }

    // ───────── プレビューの付帯情報 ─────────

    private List<AudiencePreviewResponseDto.SampleTeam> sampleTeams(List<Long> teamIds) {
        List<Long> head = teamIds.subList(0, Math.min(SAMPLE_SIZE, teamIds.size()));
        if (head.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = teamService.getNamesByIds(head);
        Map<Long, String> slugs = teamService.getSlugsByIds(head);
        List<AudiencePreviewResponseDto.SampleTeam> samples = new ArrayList<>(head.size());
        for (Long id : head) {
            String name = names.get(id);
            if (name != null) {
                samples.add(new AudiencePreviewResponseDto.SampleTeam(slugs.get(id), name));
            }
        }
        return samples;
    }

    /**
     * push が送られるか（§8.5.1）。push を出すのはアンケートだけで、送信者は組織 ADMIN・MANAGE_CONTENT を持つ
     * DEPUTY_ADMIN・SYSTEM_ADMIN のいずれかに限る。
     */
    private boolean pushEnabled(Long callerUserId, Long organizationId, AnnouncementChannel channel) {
        if (channel != AnnouncementChannel.SURVEY) {
            return false;
        }
        return accessControlService.isSystemAdmin(callerUserId)
                || accessControlService.hasAdminOrPermissionInScope(
                        callerUserId, organizationId, ORGANIZATION, PUSH_PERMISSION);
    }
}
