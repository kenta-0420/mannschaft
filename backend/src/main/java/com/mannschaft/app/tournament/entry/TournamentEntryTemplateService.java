package com.mannschaft.app.tournament.entry;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MemberDto;
import com.mannschaft.app.membership.query.MemberQueryDispatcher;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.TournamentStatus;
import com.mannschaft.app.tournament.entry.dto.ApplyTemplateRequest;
import com.mannschaft.app.tournament.entry.dto.ApplyTemplateResponse;
import com.mannschaft.app.tournament.entry.dto.CreateEntryTemplateRequest;
import com.mannschaft.app.tournament.entry.dto.EntryMemberResponse;
import com.mannschaft.app.tournament.entry.dto.EntryTemplateDetailResponse;
import com.mannschaft.app.tournament.entry.dto.EntryTemplateMemberResponse;
import com.mannschaft.app.tournament.entry.dto.EntryTemplateResponse;
import com.mannschaft.app.tournament.entry.dto.UpdateEntryTemplateRequest;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * エントリーテンプレート管理サービス。
 *
 * <p>F08.7 Phase 9-B: チームごとのエントリーテンプレートのCRUDとエントリー表への適用を担当する。</p>
 *
 * <p>設計書: docs/features/F08.7_tournament_league.md §Phase9-B</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TournamentEntryTemplateService {

    private final TournamentEntryTemplateRepository templateRepository;
    private final TournamentEntryTemplateMemberRepository templateMemberRepository;
    private final TournamentEntryMemberRepository entryMemberRepository;
    private final TournamentParticipantRepository participantRepository;
    private final TournamentRepository tournamentRepository;
    private final TournamentDivisionRepository divisionRepository;
    private final MemberQueryDispatcher memberQueryDispatcher;
    private final TeamOrgMembershipRepository teamOrgMembershipRepository;
    private final UserRepository userRepository;

    // =========================================================
    // IDOR検証ヘルパー
    // =========================================================

    /**
     * orgId → teamId の帰属確認。
     * チームが指定組織に所属していない場合は TEAM_NOT_IN_ORGANIZATION で 404。
     */
    private void validateTeamBelongsToOrg(Long orgId, Long teamId) {
        teamOrgMembershipRepository.findByTeamIdAndOrganizationId(teamId, orgId)
                .filter(m -> m.getStatus() == TeamOrgMembershipEntity.Status.ACTIVE)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.TEAM_NOT_IN_ORGANIZATION));
    }

    /**
     * IDOR検証チェーン（テンプレート適用用）: orgId → tId → divId → pId の帰属確認。
     */
    private TournamentParticipantEntity resolveParticipant(Long orgId, Long tId, Long divId, Long pId) {
        TournamentEntity tournament = tournamentRepository.findById(tId)
                .filter(t -> orgId.equals(t.getOrganizationId()))
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.PARTICIPANT_NOT_FOUND));

        divisionRepository.findByIdAndTournamentId(divId, tId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.PARTICIPANT_NOT_FOUND));

        return participantRepository.findById(pId)
                .filter(p -> divId.equals(p.getDivisionId()))
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.PARTICIPANT_NOT_FOUND));
    }

    /**
     * 編集ロック確認（エントリーテンプレート適用時）。
     */
    private void checkEntryLock(TournamentEntity tournament) {
        TournamentStatus status = tournament.getStatus();
        if (status != TournamentStatus.DRAFT && status != TournamentStatus.OPEN) {
            throw new BusinessException(TournamentErrorCode.ENTRY_LOCKED);
        }
    }

    // =========================================================
    // ユーザー名解決ヘルパー
    // =========================================================

    /**
     * userIdのリストから displayName を一括解決する。
     * TODO: UserRepository に findMemberSummaryByIdIn を追加して N+1 を解消すること
     */
    private Map<Long, String> resolveDisplayNames(List<Long> userIds) {
        Map<Long, String> result = new HashMap<>();
        for (Long userId : userIds) {
            String displayName = userRepository.findMemberSummaryById(userId)
                    .map(UserRepository.MemberSummary::getDisplayName)
                    .orElse("userId=" + userId); // TODO: UserQueryService で解決するまでのプレースホルダー
            result.put(userId, displayName);
        }
        return result;
    }

    // =========================================================
    // Entity → DTO 変換
    // =========================================================

    private EntryTemplateDetailResponse toDetailResponse(
            TournamentEntryTemplateEntity template,
            List<TournamentEntryTemplateMemberEntity> members,
            Map<Long, String> displayNames) {
        List<EntryTemplateMemberResponse> memberResponses = members.stream()
                .map(m -> EntryTemplateMemberResponse.builder()
                        .id(m.getId())
                        .userId(m.getUserId())
                        .displayName(displayNames.getOrDefault(m.getUserId(), "userId=" + m.getUserId()))
                        .jerseyNumber(m.getJerseyNumber())
                        .position(m.getPosition())
                        .sortOrder(m.getSortOrder())
                        .build())
                .toList();

        return EntryTemplateDetailResponse.builder()
                .id(template.getId())
                .name(template.getName())
                .description(template.getDescription())
                .sortOrder(template.getSortOrder())
                .members(memberResponses)
                .build();
    }

    // =========================================================
    // テンプレート CRUD
    // =========================================================

    /**
     * テンプレート一覧を取得する。
     *
     * @param orgId         組織ID
     * @param teamId        チームID
     * @param currentUserId 操作ユーザーID
     * @return テンプレート一覧
     */
    public List<EntryTemplateResponse> getTemplates(Long orgId, Long teamId, Long currentUserId) {
        validateTeamBelongsToOrg(orgId, teamId);
        List<TournamentEntryTemplateEntity> templates =
                templateRepository.findByTeamIdAndDeletedAtIsNullOrderBySortOrderAsc(teamId);

        return templates.stream()
                .map(t -> EntryTemplateResponse.builder()
                        .id(t.getId())
                        .name(t.getName())
                        .description(t.getDescription())
                        .sortOrder(t.getSortOrder())
                        .memberCount(templateMemberRepository.countByTemplateId(t.getId()))
                        .updatedAt(t.getUpdatedAt())
                        .build())
                .toList();
    }

    /**
     * テンプレート詳細を取得する。
     *
     * @param orgId         組織ID
     * @param teamId        チームID
     * @param templateId    テンプレートID
     * @param currentUserId 操作ユーザーID
     * @return テンプレート詳細（メンバー一覧付き）
     */
    public EntryTemplateDetailResponse getTemplate(Long orgId, Long teamId, UUID templateId, Long currentUserId) {
        validateTeamBelongsToOrg(orgId, teamId);
        TournamentEntryTemplateEntity template =
                templateRepository.findByIdAndTeamIdAndDeletedAtIsNull(templateId, teamId)
                        .orElseThrow(() -> new BusinessException(TournamentErrorCode.ENTRY_TEMPLATE_NOT_FOUND));

        List<TournamentEntryTemplateMemberEntity> members =
                templateMemberRepository.findByTemplateIdOrderBySortOrderAsc(templateId);
        List<Long> userIds = members.stream().map(TournamentEntryTemplateMemberEntity::getUserId).toList();
        Map<Long, String> displayNames = resolveDisplayNames(userIds);

        return toDetailResponse(template, members, displayNames);
    }

    /**
     * テンプレートを作成する（最大5件チェック）。
     *
     * @param orgId         組織ID
     * @param teamId        チームID
     * @param req           作成リクエスト
     * @param currentUserId 操作ユーザーID
     * @return 作成されたテンプレートの詳細
     */
    @Transactional
    public EntryTemplateDetailResponse createTemplate(Long orgId, Long teamId,
                                                       CreateEntryTemplateRequest req, Long currentUserId) {
        validateTeamBelongsToOrg(orgId, teamId);

        // 5件上限チェック
        long count = templateRepository.countByTeamIdAndDeletedAtIsNull(teamId);
        if (count >= 5) {
            throw new BusinessException(TournamentErrorCode.MAX_TEMPLATE_COUNT_EXCEEDED);
        }

        TournamentEntryTemplateEntity template = TournamentEntryTemplateEntity.builder()
                .teamId(teamId)
                .name(req.getName())
                .description(req.getDescription())
                .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                .build();
        TournamentEntryTemplateEntity saved = templateRepository.save(template);

        List<TournamentEntryTemplateMemberEntity> members = req.getMembers().stream()
                .map(item -> (TournamentEntryTemplateMemberEntity) TournamentEntryTemplateMemberEntity.builder()
                        .templateId(saved.getId())
                        .userId(item.getUserId())
                        .jerseyNumber(item.getJerseyNumber())
                        .position(item.getPosition())
                        .sortOrder(item.getSortOrder() != null ? item.getSortOrder() : 0)
                        .build())
                .toList();
        templateMemberRepository.saveAll(members);

        List<TournamentEntryTemplateMemberEntity> savedMembers =
                templateMemberRepository.findByTemplateIdOrderBySortOrderAsc(saved.getId());
        List<Long> userIds = savedMembers.stream().map(TournamentEntryTemplateMemberEntity::getUserId).toList();
        Map<Long, String> displayNames = resolveDisplayNames(userIds);

        return toDetailResponse(saved, savedMembers, displayNames);
    }

    /**
     * テンプレートを更新する（membersは全置換）。
     *
     * <p>差分更新推奨だが全置換で実装（templateMemberRepository.deleteByTemplateId → saveAll）。
     * 将来的にはdiff計算による差分更新に変更すること。</p>
     *
     * @param orgId         組織ID
     * @param teamId        チームID
     * @param templateId    テンプレートID
     * @param req           更新リクエスト
     * @param currentUserId 操作ユーザーID
     * @return 更新後のテンプレート詳細
     */
    @Transactional
    public EntryTemplateDetailResponse updateTemplate(Long orgId, Long teamId, UUID templateId,
                                                       UpdateEntryTemplateRequest req, Long currentUserId) {
        validateTeamBelongsToOrg(orgId, teamId);
        TournamentEntryTemplateEntity template =
                templateRepository.findByIdAndTeamIdAndDeletedAtIsNull(templateId, teamId)
                        .orElseThrow(() -> new BusinessException(TournamentErrorCode.ENTRY_TEMPLATE_NOT_FOUND));

        // テンプレート情報を更新
        template.update(req.getName(), req.getDescription(),
                req.getSortOrder() != null ? req.getSortOrder() : 0);
        templateRepository.save(template);

        // メンバーを全置換（差分更新推奨だが全置換で実装）
        templateMemberRepository.deleteByTemplateId(templateId);
        List<TournamentEntryTemplateMemberEntity> newMembers = req.getMembers().stream()
                .map(item -> (TournamentEntryTemplateMemberEntity) TournamentEntryTemplateMemberEntity.builder()
                        .templateId(templateId)
                        .userId(item.getUserId())
                        .jerseyNumber(item.getJerseyNumber())
                        .position(item.getPosition())
                        .sortOrder(item.getSortOrder() != null ? item.getSortOrder() : 0)
                        .build())
                .toList();
        templateMemberRepository.saveAll(newMembers);

        List<TournamentEntryTemplateMemberEntity> savedMembers =
                templateMemberRepository.findByTemplateIdOrderBySortOrderAsc(templateId);
        List<Long> userIds = savedMembers.stream().map(TournamentEntryTemplateMemberEntity::getUserId).toList();
        Map<Long, String> displayNames = resolveDisplayNames(userIds);

        return toDetailResponse(template, savedMembers, displayNames);
    }

    /**
     * テンプレートを論理削除する。
     *
     * @param orgId         組織ID
     * @param teamId        チームID
     * @param templateId    テンプレートID
     * @param currentUserId 操作ユーザーID
     */
    @Transactional
    public void deleteTemplate(Long orgId, Long teamId, UUID templateId, Long currentUserId) {
        validateTeamBelongsToOrg(orgId, teamId);
        TournamentEntryTemplateEntity template =
                templateRepository.findByIdAndTeamIdAndDeletedAtIsNull(templateId, teamId)
                        .orElseThrow(() -> new BusinessException(TournamentErrorCode.ENTRY_TEMPLATE_NOT_FOUND));

        template.softDelete();
        templateRepository.save(template);
    }

    // =========================================================
    // テンプレート適用
    // =========================================================

    /**
     * テンプレートをエントリー表に適用する。
     *
     * @param orgId         組織ID
     * @param tId           大会ID
     * @param divId         ディビジョンID
     * @param pId           参加チームID
     * @param req           適用リクエスト
     * @param currentUserId 操作ユーザーID
     * @return 適用結果レスポンス
     */
    @Transactional
    public ApplyTemplateResponse applyTemplate(Long orgId, Long tId, Long divId, Long pId,
                                                ApplyTemplateRequest req, Long currentUserId) {
        // 1. IDOR検証チェーン
        TournamentParticipantEntity participant = resolveParticipant(orgId, tId, divId, pId);
        TournamentEntity tournament = tournamentRepository.findById(tId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND));

        // 2. 編集ロック確認
        checkEntryLock(tournament);

        // 3. テンプレートを取得し、participant.teamId との一致確認（TOUR_028）
        TournamentEntryTemplateEntity template = templateRepository.findById(req.getTemplateId())
                .filter(t -> t.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.ENTRY_TEMPLATE_NOT_FOUND));

        if (!participant.getTeamId().equals(template.getTeamId())) {
            throw new BusinessException(TournamentErrorCode.TEMPLATE_TEAM_MISMATCH);
        }

        // 4. テンプレートメンバーを取得
        List<TournamentEntryTemplateMemberEntity> templateMembers =
                templateMemberRepository.findByTemplateIdOrderBySortOrderAsc(req.getTemplateId());

        // 5. アクティブメンバーのuserIdセットを取得（非アクティブメンバーのスキップ判定用）
        // TODO: memberQueryDispatcher はチームドメインをまたぐ。将来はイベント駆動化予定
        Set<Long> activeMemberIds = memberQueryDispatcher
                .queryMembers(participant.getTeamId(), ScopeType.TEAM, null)
                .stream()
                .map(MemberDto::userId)
                .collect(Collectors.toSet());

        // 6. 既存エントリーのuserIdセット取得
        Set<Long> existingUserIds = entryMemberRepository.findUserIdsByParticipantId(pId);

        int applied = 0;
        int skipped = 0;
        int skippedInactive = 0;
        List<TournamentEntryMemberEntity> newEntries = new ArrayList<>();

        for (TournamentEntryTemplateMemberEntity tm : templateMembers) {
            Long userId = tm.getUserId();

            // 非アクティブメンバーはスキップ
            if (!activeMemberIds.contains(userId)) {
                skippedInactive++;
                continue;
            }

            // 既存エントリー済みユーザーの処理
            if (existingUserIds.contains(userId)) {
                if (!req.isOverwriteExisting()) {
                    skipped++;
                    continue;
                }
                // overwriteExisting=true の場合もスキップ（既存エントリーはそのまま保持）
                skipped++;
                continue;
            }

            // 新規追加
            newEntries.add(TournamentEntryMemberEntity.builder()
                    .participantId(pId)
                    .userId(userId)
                    .jerseyNumber(tm.getJerseyNumber())
                    .position(tm.getPosition())
                    .sortOrder(tm.getSortOrder())
                    .build());
            applied++;
        }

        if (!newEntries.isEmpty()) {
            entryMemberRepository.saveAll(newEntries);
        }

        // 適用後の一覧を取得
        List<TournamentEntryMemberEntity> allEntries =
                entryMemberRepository.findByParticipantIdOrderBySortOrderAsc(pId);
        List<Long> allUserIds = allEntries.stream().map(TournamentEntryMemberEntity::getUserId).toList();
        Map<Long, String> displayNames = resolveDisplayNames(allUserIds);

        List<EntryMemberResponse> entryMemberResponses = allEntries.stream()
                .map(e -> EntryMemberResponse.builder()
                        .id(e.getId())
                        .participantId(e.getParticipantId())
                        .userId(e.getUserId())
                        .displayName(displayNames.getOrDefault(e.getUserId(), "userId=" + e.getUserId()))
                        .memberNumber(e.getMemberNumber())
                        .position(e.getPosition())
                        .jerseyNumber(e.getJerseyNumber())
                        .notes(e.getNotes())
                        .sortOrder(e.getSortOrder())
                        .createdAt(e.getCreatedAt())
                        .updatedAt(e.getUpdatedAt())
                        .build())
                .toList();

        return ApplyTemplateResponse.builder()
                .applied(applied)
                .skipped(skipped)
                .skippedInactive(skippedInactive)
                .total(entryMemberResponses.size())
                .entryMembers(entryMemberResponses)
                .build();
    }
}
