package com.mannschaft.app.tournament.entry;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MemberDto;
import com.mannschaft.app.membership.query.MemberQueryDispatcher;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.TournamentStatus;
import com.mannschaft.app.tournament.entry.dto.EntryLoadResponse;
import com.mannschaft.app.tournament.entry.dto.EntryMemberListResponse;
import com.mannschaft.app.tournament.entry.dto.EntryMemberResponse;
import com.mannschaft.app.tournament.entry.dto.EntryMemberSummaryItemResponse;
import com.mannschaft.app.tournament.entry.dto.EntryMemberSummaryResponse;
import com.mannschaft.app.tournament.entry.dto.LoadFromTeamRequest;
import com.mannschaft.app.tournament.entry.dto.TeamMemberCandidateResponse;
import com.mannschaft.app.tournament.entry.dto.UpsertEntryMembersRequest;
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
 * 大会エントリー表メンバー管理サービス。
 *
 * <p>F08.7 Phase 9: エントリー表の取得・一括ロード・全置換・削除・PDF出力・サマリー取得を担当する。</p>
 *
 * <p>設計書: docs/features/F08.7_tournament_league.md §Phase9</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TournamentEntryMemberService {

    private final TournamentEntryMemberRepository entryMemberRepository;
    private final TournamentEntryTemplateRepository templateRepository;
    private final TournamentEntryTemplateMemberRepository templateMemberRepository;
    private final TournamentRepository tournamentRepository;
    private final TournamentParticipantRepository participantRepository;
    private final TournamentDivisionRepository divisionRepository;
    private final MemberQueryDispatcher memberQueryDispatcher;
    private final UserRepository userRepository;
    private final PdfGeneratorService pdfGeneratorService;
    private final AccessControlService accessControlService;

    // =========================================================
    // IDOR検証チェーン（存在束縛）
    // =========================================================

    /**
     * 大会をパスの組織 ID に束縛して取得する。不存在・他組織の大会は 404 で存在秘匿する。
     *
     * @param orgId        パスの組織 ID
     * @param tId          大会 ID
     * @param notFoundCode 不一致時に投げるエラーコード（404 マップ済みであること）
     * @return パス org に属する大会エンティティ
     */
    private TournamentEntity resolveTournamentInOrg(Long orgId, Long tId, ErrorCode notFoundCode) {
        return tournamentRepository.findById(tId)
                .filter(t -> orgId.equals(t.getOrganizationId()))
                .orElseThrow(() -> new BusinessException(notFoundCode));
    }

    /**
     * IDOR検証チェーン: tId → divId → pId の帰属を確認し、参加チームを返す。
     * いずれか失敗した場合は PARTICIPANT_NOT_FOUND で 404 を返す。
     *
     * @param tId   {@link #resolveTournamentInOrg} で org 束縛済みの大会 ID
     * @param divId ディビジョン ID
     * @param pId   参加チーム ID
     * @return 当該ディビジョン配下の参加チーム
     */
    private TournamentParticipantEntity resolveParticipant(Long tId, Long divId, Long pId) {
        divisionRepository.findByIdAndTournamentId(divId, tId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.PARTICIPANT_NOT_FOUND));

        return participantRepository.findById(pId)
                .filter(p -> divId.equals(p.getDivisionId()))
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.PARTICIPANT_NOT_FOUND));
    }

    // =========================================================
    // scope 認可（BOLA 回避: 認可 scope は必ずエンティティ由来の値で判定する）
    // =========================================================

    /**
     * エントリー表の閲覧権限を検証する。
     *
     * <p>根拠: 設計書 {@code docs/features/F08.7_tournament_league.md} §Phase9 API 一覧
     * 「エントリー一覧＋チームメンバー候補取得 / エントリー表PDF出力 = MEMBER+（自チームのみ）」および
     * 同 §テスト観点「{@code GET /entry-members 403 他チームユーザーはアクセス不可}」。</p>
     *
     * <p>エントリー表は選手の {@code userId} と実名を保持するため、参加チーム
     * （<b>エンティティ由来</b> {@code participant.teamId}）の MEMBER 以上、または主催組織
     * （<b>エンティティ由来</b> {@code tournament.organizationId}）の ADMIN/DEPUTY_ADMIN のみ許可する。
     * 主催組織 ADMIN を許すのは、主催者がエントリー状況を確認・是正する運用があるため
     * （同 API 一覧の書込権限に「ADMIN, DEPUTY_ADMIN(MANAGE_TOURNAMENT)」が並ぶのと整合）。</p>
     */
    private void checkEntryViewable(Long currentUserId, TournamentEntity tournament,
                                    TournamentParticipantEntity participant) {
        if (accessControlService.isSystemAdmin(currentUserId)) {
            return;
        }
        if (accessControlService.isMember(currentUserId, participant.getTeamId(), "TEAM")) {
            return;
        }
        if (accessControlService.isAdminOrAbove(
                currentUserId, tournament.getOrganizationId(), "ORGANIZATION")) {
            return;
        }
        throw new BusinessException(CommonErrorCode.COMMON_002);
    }

    /**
     * エントリー表の編集権限を検証する。
     *
     * <p>根拠: 設計書 §Phase9 API 一覧「一括ロード / 全置換 / 個別削除 =
     * ADMIN, DEPUTY_ADMIN(MANAGE_TOURNAMENT), チームADMIN」。参加チーム（エンティティ由来）の
     * ADMIN/DEPUTY_ADMIN、または主催組織（エンティティ由来）の ADMIN/DEPUTY_ADMIN のみ許可する。</p>
     */
    private void checkEntryManageable(Long currentUserId, TournamentEntity tournament,
                                      TournamentParticipantEntity participant) {
        if (accessControlService.isSystemAdmin(currentUserId)) {
            return;
        }
        if (accessControlService.isAdminOrAbove(currentUserId, participant.getTeamId(), "TEAM")) {
            return;
        }
        if (accessControlService.isAdminOrAbove(
                currentUserId, tournament.getOrganizationId(), "ORGANIZATION")) {
            return;
        }
        throw new BusinessException(CommonErrorCode.COMMON_002);
    }

    /**
     * 主催者向け集計（全チーム横断）の権限を検証する。
     *
     * <p>根拠: 設計書 §Phase9「全チームエントリーサマリー（主催者向け）」。全参加チームの
     * エントリー充足状況を横断表示するため、主催組織（<b>エンティティ由来</b>
     * {@code tournament.organizationId}）の ADMIN/DEPUTY_ADMIN 限定とする
     * （FE も {@code isAdminOrDeputy} でのみ本 API を呼ぶ）。</p>
     */
    private void checkOrganizerAdmin(Long currentUserId, TournamentEntity tournament) {
        if (accessControlService.isSystemAdmin(currentUserId)) {
            return;
        }
        if (!accessControlService.isAdminOrAbove(
                currentUserId, tournament.getOrganizationId(), "ORGANIZATION")) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * 編集ロック確認。
     * DRAFT / OPEN のみ編集可能。IN_PROGRESS 時は管理者ロールがあれば緊急修正可。
     */
    private void checkEntryLock(TournamentEntity tournament, boolean hasTournamentAdminRole) {
        TournamentStatus status = tournament.getStatus();
        if (status == TournamentStatus.IN_PROGRESS && hasTournamentAdminRole) {
            return; // 緊急修正可
        }
        if (status != TournamentStatus.DRAFT && status != TournamentStatus.OPEN) {
            throw new BusinessException(TournamentErrorCode.ENTRY_LOCKED);
        }
    }

    /**
     * 人数バリデーション。
     * min/max が null の場合は制限なし。
     */
    private void validateEntryCount(int count, Integer min, Integer max) {
        if (min != null && count < min) {
            throw new BusinessException(TournamentErrorCode.MIN_ENTRY_COUNT_VIOLATION);
        }
        if (max != null && count > max) {
            throw new BusinessException(TournamentErrorCode.MAX_ENTRY_COUNT_EXCEEDED);
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
    // エントリーメンバー → DTO 変換
    // =========================================================

    private EntryMemberResponse toResponse(TournamentEntryMemberEntity entity, Map<Long, String> displayNames) {
        return EntryMemberResponse.builder()
                .id(entity.getId())
                .participantId(entity.getParticipantId())
                .userId(entity.getUserId())
                .displayName(displayNames.getOrDefault(entity.getUserId(), "userId=" + entity.getUserId()))
                .memberNumber(entity.getMemberNumber())
                .position(entity.getPosition())
                .jerseyNumber(entity.getJerseyNumber())
                .notes(entity.getNotes())
                .sortOrder(entity.getSortOrder())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    // =========================================================
    // エントリーメンバー CRUD
    // =========================================================

    /**
     * エントリー表メンバー一覧を取得する。
     *
     * @param orgId               組織ID
     * @param tId                 大会ID
     * @param divId               ディビジョンID
     * @param pId                 参加チームID
     * @param includeTeamMembers  チームメンバー候補を含めるかどうか
     * @param currentUserId       操作ユーザーID
     * @return エントリーメンバー一覧レスポンス
     */
    public EntryMemberListResponse getEntryMembers(Long orgId, Long tId, Long divId, Long pId,
                                                    boolean includeTeamMembers, Long currentUserId) {
        TournamentEntity tournament =
                resolveTournamentInOrg(orgId, tId, TournamentErrorCode.PARTICIPANT_NOT_FOUND);
        TournamentParticipantEntity participant = resolveParticipant(tId, divId, pId);
        checkEntryViewable(currentUserId, tournament, participant);
        TournamentDivisionEntity division = divisionRepository.findById(divId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.DIVISION_NOT_FOUND));

        List<TournamentEntryMemberEntity> entities =
                entryMemberRepository.findByParticipantIdOrderBySortOrderAsc(pId);
        List<Long> userIds = entities.stream().map(TournamentEntryMemberEntity::getUserId).toList();
        Map<Long, String> displayNames = resolveDisplayNames(userIds);

        List<EntryMemberResponse> entryMemberResponses = entities.stream()
                .map(e -> toResponse(e, displayNames))
                .toList();

        // チームメンバー候補の取得
        List<TeamMemberCandidateResponse> candidates = null;
        if (includeTeamMembers) {
            Set<Long> enteredUserIds = entities.stream()
                    .map(TournamentEntryMemberEntity::getUserId)
                    .collect(Collectors.toSet());

            // TODO: memberQueryDispatcher はチームスコープのメンバーを返すが、
            //  memberNumber/position はチームメンバーのみ保持する情報のため、
            //  将来的に TeamMemberRepository 経由で解決すること
            List<MemberDto> teamMembers = memberQueryDispatcher.queryMembers(
                    participant.getTeamId(), ScopeType.TEAM, null);

            candidates = teamMembers.stream()
                    .map(m -> TeamMemberCandidateResponse.builder()
                            .userId(m.userId())
                            .displayName(m.displayName())
                            .memberNumber(null)   // TODO: TeamMemberRepository から解決
                            .position(null)       // TODO: TeamMemberRepository から解決
                            .isAlreadyEntered(enteredUserIds.contains(m.userId()))
                            .build())
                    .toList();
        }

        return EntryMemberListResponse.builder()
                .entryMembers(entryMemberResponses)
                .teamMemberCandidates(candidates)
                .entryCount(entryMemberResponses.size())
                .minEntryCount(division.getMinEntryCount())
                .maxEntryCount(division.getMaxEntryCount())
                .build();
    }

    /**
     * チームメンバーからエントリー表を一括ロードする。
     *
     * <p>TODO: memberドメインをまたいでいる。将来はTeamMemberLoadRequestedEventで分離予定</p>
     *
     * @param orgId           組織ID
     * @param tId             大会ID
     * @param divId           ディビジョンID
     * @param pId             参加チームID
     * @param req             ロードリクエスト
     * @param currentUserId   操作ユーザーID
     * @return ロード結果レスポンス
     */
    @Transactional
    public EntryLoadResponse loadFromTeamMembers(Long orgId, Long tId, Long divId, Long pId,
                                                  LoadFromTeamRequest req, Long currentUserId) {
        TournamentEntity tournament =
                resolveTournamentInOrg(orgId, tId, TournamentErrorCode.PARTICIPANT_NOT_FOUND);
        TournamentParticipantEntity participant = resolveParticipant(tId, divId, pId);
        checkEntryManageable(currentUserId, tournament, participant);
        TournamentDivisionEntity division = divisionRepository.findById(divId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.DIVISION_NOT_FOUND));

        checkEntryLock(tournament, false);

        // TODO: memberQueryDispatcher はトランザクション境界の外で呼ぶことを推奨だが、
        //  現実装では同一トランザクション内で呼んでいる。将来のイベント駆動化で分離予定
        List<MemberDto> activeMembers = memberQueryDispatcher.queryMembers(
                participant.getTeamId(), ScopeType.TEAM, null);

        List<Long> targetUserIds;
        if (req.getUserIds() != null && !req.getUserIds().isEmpty()) {
            // userIds指定がある場合: チームメンバーであることを確認
            Set<Long> activeMemberIds = activeMembers.stream()
                    .map(MemberDto::userId)
                    .collect(Collectors.toSet());
            List<Long> invalidIds = req.getUserIds().stream()
                    .filter(uid -> !activeMemberIds.contains(uid))
                    .toList();
            if (!invalidIds.isEmpty()) {
                throw new BusinessException(TournamentErrorCode.USER_NOT_TEAM_MEMBER);
            }
            targetUserIds = req.getUserIds();
        } else {
            // userIds未指定: 全アクティブメンバーを対象
            targetUserIds = activeMembers.stream().map(MemberDto::userId).toList();
        }

        // 既存エントリーのuserIdセットを取得
        Set<Long> existingUserIds = entryMemberRepository.findUserIdsByParticipantId(pId);

        int added = 0;
        int skipped = 0;
        List<TournamentEntryMemberEntity> newEntries = new ArrayList<>();

        for (Long userId : targetUserIds) {
            if (existingUserIds.contains(userId) && !req.isOverwriteExisting()) {
                skipped++;
                continue;
            }
            if (existingUserIds.contains(userId) && req.isOverwriteExisting()) {
                // 上書きの場合: 既存エントリーはそのまま（jerseyNumber等の情報を保持）
                skipped++;
                continue;
            }
            newEntries.add(TournamentEntryMemberEntity.builder()
                    .participantId(pId)
                    .userId(userId)
                    .sortOrder((short) (existingUserIds.size() + added))
                    .build());
            added++;
        }

        if (!newEntries.isEmpty()) {
            entryMemberRepository.saveAll(newEntries);
        }

        // ロード後の人数バリデーション（maxのみチェック、minは保存時に緩和）
        long totalCount = entryMemberRepository.countByParticipantId(pId);
        if (division.getMaxEntryCount() != null && totalCount > division.getMaxEntryCount()) {
            throw new BusinessException(TournamentErrorCode.MAX_ENTRY_COUNT_EXCEEDED);
        }

        List<TournamentEntryMemberEntity> allEntries =
                entryMemberRepository.findByParticipantIdOrderBySortOrderAsc(pId);
        List<Long> allUserIds = allEntries.stream().map(TournamentEntryMemberEntity::getUserId).toList();
        Map<Long, String> displayNames = resolveDisplayNames(allUserIds);

        return EntryLoadResponse.builder()
                .added(added)
                .skipped(skipped)
                .total((int) totalCount)
                .entryMembers(allEntries.stream().map(e -> toResponse(e, displayNames)).toList())
                .build();
    }

    /**
     * エントリー表メンバーを全置換（確定保存）する。
     *
     * @param orgId           組織ID
     * @param tId             大会ID
     * @param divId           ディビジョンID
     * @param pId             参加チームID
     * @param req             全置換リクエスト
     * @param currentUserId   操作ユーザーID
     * @return 更新後のエントリーメンバー一覧
     */
    @Transactional
    public EntryMemberListResponse upsertEntryMembers(Long orgId, Long tId, Long divId, Long pId,
                                                       UpsertEntryMembersRequest req, Long currentUserId) {
        TournamentEntity tournament =
                resolveTournamentInOrg(orgId, tId, TournamentErrorCode.PARTICIPANT_NOT_FOUND);
        TournamentParticipantEntity participant = resolveParticipant(tId, divId, pId);
        checkEntryManageable(currentUserId, tournament, participant);
        TournamentDivisionEntity division = divisionRepository.findById(divId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.DIVISION_NOT_FOUND));

        checkEntryLock(tournament, false);
        validateEntryCount(req.getMembers().size(), division.getMinEntryCount(), division.getMaxEntryCount());

        // 既存エントリーを全削除して再INSERT
        entryMemberRepository.deleteByParticipantId(pId);

        List<TournamentEntryMemberEntity> newEntries = req.getMembers().stream()
                .map(item -> (TournamentEntryMemberEntity) TournamentEntryMemberEntity.builder()
                        .participantId(pId)
                        .userId(item.getUserId())
                        .jerseyNumber(item.getJerseyNumber())
                        .position(item.getPosition())
                        .notes(item.getNotes())
                        .sortOrder(item.getSortOrder() != null ? item.getSortOrder() : 0)
                        .build())
                .toList();

        entryMemberRepository.saveAll(newEntries);

        List<TournamentEntryMemberEntity> savedEntries =
                entryMemberRepository.findByParticipantIdOrderBySortOrderAsc(pId);
        List<Long> userIds = savedEntries.stream().map(TournamentEntryMemberEntity::getUserId).toList();
        Map<Long, String> displayNames = resolveDisplayNames(userIds);

        List<EntryMemberResponse> entryMemberResponses = savedEntries.stream()
                .map(e -> toResponse(e, displayNames))
                .toList();

        return EntryMemberListResponse.builder()
                .entryMembers(entryMemberResponses)
                .teamMemberCandidates(null)
                .entryCount(entryMemberResponses.size())
                .minEntryCount(division.getMinEntryCount())
                .maxEntryCount(division.getMaxEntryCount())
                .build();
    }

    /**
     * エントリーメンバーを個別削除する。
     *
     * @param orgId           組織ID
     * @param tId             大会ID
     * @param divId           ディビジョンID
     * @param pId             参加チームID
     * @param entryMemberId   削除対象のエントリーメンバーID
     * @param force           強制削除フラグ（ロック中でも削除可能）
     * @param currentUserId   操作ユーザーID
     */
    @Transactional
    public void deleteEntryMember(Long orgId, Long tId, Long divId, Long pId, UUID entryMemberId,
                                   boolean force, Long currentUserId) {
        TournamentEntity tournament =
                resolveTournamentInOrg(orgId, tId, TournamentErrorCode.PARTICIPANT_NOT_FOUND);
        TournamentParticipantEntity participant = resolveParticipant(tId, divId, pId);
        checkEntryManageable(currentUserId, tournament, participant);

        if (!force) {
            checkEntryLock(tournament, false);
        }

        TournamentEntryMemberEntity entry = entryMemberRepository.findById(entryMemberId)
                .filter(e -> pId.equals(e.getParticipantId()))
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.ENTRY_MEMBER_NOT_FOUND));

        entryMemberRepository.delete(entry);
    }

    /**
     * エントリー表PDFを生成する（バイト列返却）。
     *
     * @param orgId           組織ID
     * @param tId             大会ID
     * @param divId           ディビジョンID
     * @param pId             参加チームID
     * @param currentUserId   操作ユーザーID
     * @return PDF のbyte[]
     */
    public byte[] generateEntryPdf(Long orgId, Long tId, Long divId, Long pId, Long currentUserId) {
        TournamentEntity tournament =
                resolveTournamentInOrg(orgId, tId, TournamentErrorCode.PARTICIPANT_NOT_FOUND);
        TournamentParticipantEntity participant = resolveParticipant(tId, divId, pId);
        checkEntryViewable(currentUserId, tournament, participant);
        TournamentDivisionEntity division = divisionRepository.findById(divId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.DIVISION_NOT_FOUND));

        List<TournamentEntryMemberEntity> entities =
                entryMemberRepository.findByParticipantIdOrderBySortOrderAsc(pId);
        List<Long> userIds = entities.stream().map(TournamentEntryMemberEntity::getUserId).toList();
        Map<Long, String> displayNames = resolveDisplayNames(userIds);

        List<EntryMemberResponse> entryMembers = entities.stream()
                .map(e -> toResponse(e, displayNames))
                .toList();

        Map<String, Object> variables = new HashMap<>();
        variables.put("tournamentName", tournament.getName());
        variables.put("divisionName", division.getName());
        variables.put("teamDisplayName", participant.getDisplayName());
        variables.put("outputDate", java.time.LocalDate.now());
        variables.put("entryMembers", entryMembers);
        variables.put("totalCount", entryMembers.size());

        return pdfGeneratorService.generateFromTemplate("pdf/tournament-entry-members", variables);
    }

    /**
     * 全チームエントリーサマリーを取得する（主催者向け）。
     *
     * @param orgId           組織ID
     * @param tId             大会ID
     * @param divId           ディビジョンID
     * @param currentUserId   操作ユーザーID
     * @return ディビジョン単位のエントリーサマリー
     */
    public EntryMemberSummaryResponse getEntrySummary(Long orgId, Long tId, Long divId, Long currentUserId) {
        // orgId → tId の帰属確認（不一致は 404 で存在秘匿）
        TournamentEntity tournament =
                resolveTournamentInOrg(orgId, tId, TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        // 主催者向け横断集計のため主催組織 ADMIN/DEPUTY_ADMIN 限定
        checkOrganizerAdmin(currentUserId, tournament);

        TournamentDivisionEntity division = divisionRepository.findByIdAndTournamentId(divId, tId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.DIVISION_NOT_FOUND));

        List<TournamentParticipantEntity> participants =
                participantRepository.findByDivisionIdOrderBySeedAsc(divId);
        List<Long> participantIds = participants.stream().map(TournamentParticipantEntity::getId).toList();

        // 一括でエントリー数を集計
        Map<Long, TournamentEntryMemberRepository.EntryCountProjection> countMap =
                entryMemberRepository.countByParticipantIdIn(participantIds).stream()
                        .collect(Collectors.toMap(
                                TournamentEntryMemberRepository.EntryCountProjection::getParticipantId,
                                p -> p));

        Integer minCount = division.getMinEntryCount();
        Integer maxCount = division.getMaxEntryCount();

        List<EntryMemberSummaryItemResponse> summaryItems = participants.stream()
                .map(p -> {
                    TournamentEntryMemberRepository.EntryCountProjection proj = countMap.get(p.getId());
                    long entryCount = proj != null ? proj.getEntryCount() : 0L;
                    return EntryMemberSummaryItemResponse.builder()
                            .participantId(p.getId())
                            .teamId(p.getTeamId())
                            .displayName(p.getDisplayName())
                            .entryCount(entryCount)
                            .isMinMet(minCount == null || entryCount >= minCount)
                            .isMaxExceeded(maxCount != null && entryCount > maxCount)
                            .lastUpdatedAt(proj != null ? proj.getLastUpdatedAt() : null)
                            .build();
                })
                .toList();

        return EntryMemberSummaryResponse.builder()
                .divisionId(divId)
                .divisionName(division.getName())
                .minEntryCount(minCount)
                .maxEntryCount(maxCount)
                .summary(summaryItems)
                .build();
    }
}
