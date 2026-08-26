package com.mannschaft.app.tournament.submission;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.forms.SubmissionStatus;
import com.mannschaft.app.forms.dto.CreateFormSubmissionRequest;
import com.mannschaft.app.forms.dto.FormSubmissionResponse;
import com.mannschaft.app.forms.entity.FormSubmissionEntity;
import com.mannschaft.app.forms.entity.FormTemplateEntity;
import com.mannschaft.app.forms.repository.FormSubmissionRepository;
import com.mannschaft.app.forms.service.FormSubmissionService;
import com.mannschaft.app.forms.service.FormTemplateService;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.fee.TournamentFeeService;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.submission.dto.CreateSubmissionRequirementRequest;
import com.mannschaft.app.tournament.submission.dto.SubmissionRequirementResponse;
import com.mannschaft.app.tournament.submission.dto.SubmissionStatusDashboardResponse;
import com.mannschaft.app.tournament.submission.dto.SubmissionStatusDashboardResponse.TeamSubmissionStatus;
import com.mannschaft.app.tournament.submission.dto.UpdateSubmissionRequirementRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 大会ごとの書類提出受付ファサードサービス（F08.7.1/06）。
 *
 * <p><strong>汎用の提出／承認エンジンは新規に作らない。</strong> 提出の実体・承認フローは F05.6 の
 * {@code form_templates} / {@code form_submissions} / {@code workflow_requests} をそのまま再利用し、
 * 本サービスは「大会／ディビジョンと form_template を薄い連結テーブル
 * {@link TournamentSubmissionRequirementEntity} で結ぶ」ファサードに徹する。提出の実保存は
 * {@link FormSubmissionService#createSubmissionForRequirement} へ委譲する（設計書 §1・§4・§5）。</p>
 *
 * <h2>連結（設計書 §2.1 B-3 根治）</h2>
 * <p>{@code workflow_requests.source_id} は BIGINT のため UUID の requirement_id を入れられない。
 * よって提出と提出枠の対応は {@code form_submissions.tournament_submission_requirement_id}（BINARY(16)）で持つ。
 * workflow ↔ form_submission の native 連結（BIGINT 同士）は一切変更しない。</p>
 *
 * <h2>認可（設計書 §7）</h2>
 * <ul>
 *   <li>提出枠の定義／更新／削除／状況閲覧: 主催組織 ADMIN ／ SYSTEM_ADMIN。</li>
 *   <li>自チーム分の提出: 当該チームの ADMIN/DEPUTY_ADMIN のみ。他チームの提出は操作不可（403）。</li>
 *   <li>提出枠一覧: 主催組織 ADMIN は全件、参加チーム ADMIN/DEPUTY は自チームが対象の枠のみ。</li>
 * </ul>
 *
 * <p>存在しない／論理削除済み／他組織の提出枠・大会は一律 404（IDOR 対策）。
 * 全 read 経路で帰属チェックを通し、他チームの提出内容・添付が漏れないことを保証する。</p>
 *
 * <p><strong>越境（原則5）TODO:</strong> 本サービスは tournament ドメインから forms ドメインの
 * {@code FormTemplateService} / {@code FormSubmissionService} / {@code FormSubmissionRepository} を直接呼ぶ。
 * 提出の連結は読み取り主体で結合度が低いため当面は直接呼び出しとし、将来は
 * {@code TournamentSubmissionCreatedEvent} 等によるイベント駆動化を検討する。</p>
 *
 * <p>設計書: docs/features/F08.7.1_tournament_extensions/06_document_submission.md</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TournamentSubmissionRequirementService {

    private final TournamentSubmissionRequirementRepository requirementRepository;
    private final TournamentSubmissionRequirementTargetRepository targetRepository;
    private final TournamentRepository tournamentRepository;
    private final TournamentDivisionRepository divisionRepository;
    private final TournamentParticipantRepository participantRepository;
    private final AccessControlService accessControlService;
    // --- forms ドメインへの越境（原則5 TODO・上記クラスコメント参照） ---
    private final FormTemplateService formTemplateService;
    private final FormSubmissionService formSubmissionService;
    private final FormSubmissionRepository formSubmissionRepository;
    // --- payment ゲート（領域⑦連携） ---
    private final TournamentFeeService tournamentFeeService;

    private static final String SUBMISSION_SCOPE_TYPE = "TEAM";

    // ========================================================================
    // 提出枠の定義・一覧・更新・削除（主催組織 ADMIN）
    // ========================================================================

    /**
     * 提出枠を定義する（主催組織 ADMIN / SYSTEM_ADMIN）。
     *
     * @throws BusinessException SUBMISSION_REQ_MANAGE_FORBIDDEN（403）／TOURNAMENT_NOT_FOUND（404）／
     *                           DIVISION_NOT_FOUND（404）／SUBMISSION_TEMPLATE_SCOPE_MISMATCH（422）
     */
    @Transactional
    public SubmissionRequirementResponse createRequirement(Long organizationId, Long tournamentId, Long userId,
                                                           CreateSubmissionRequirementRequest request) {
        findTournamentInOrgOrThrow(organizationId, tournamentId);
        requireOrganizerAdmin(userId, organizationId);

        if (request.getDivisionId() != null) {
            verifyDivisionBelongsToTournament(request.getDivisionId(), tournamentId);
        }
        // form_template が主催組織に属することを検証（クロス組織の流用を防ぐ）
        requireOrganizationTemplate(request.getFormTemplateId(), organizationId);

        SubmissionTargetScope scope = request.getTargetScope() != null
                ? SubmissionTargetScope.valueOf(request.getTargetScope())
                : SubmissionTargetScope.ALL_TEAMS;

        TournamentSubmissionRequirementEntity req = TournamentSubmissionRequirementEntity.builder()
                .tournamentId(tournamentId)
                .divisionId(request.getDivisionId())
                .formTemplateId(request.getFormTemplateId())
                .title(request.getTitle())
                .description(request.getDescription())
                .deadline(request.getDeadline())
                .targetScope(scope)
                .requiresPayment(Boolean.TRUE.equals(request.getRequiresPayment()))
                .organizationId(organizationId)
                .createdBy(userId)
                .build();
        TournamentSubmissionRequirementEntity saved = requirementRepository.save(req);

        List<Long> targetTeamIds = persistTargets(saved.getId(), scope, request.getTeamIds());

        log.info("提出枠作成: requirementId={}, tournamentId={}, formTemplateId={}, scope={}",
                saved.getId(), tournamentId, request.getFormTemplateId(), scope);
        return SubmissionRequirementResponse.of(saved, targetTeamIds);
    }

    /**
     * 主催者向け: 大会の提出枠一覧（全件）を取得する（主催組織 ADMIN / SYSTEM_ADMIN）。
     *
     * @throws BusinessException SUBMISSION_REQ_MANAGE_FORBIDDEN（403）／TOURNAMENT_NOT_FOUND（404）
     */
    public List<SubmissionRequirementResponse> listRequirementsForOrganizer(Long organizationId, Long tournamentId,
                                                                            Long userId) {
        findTournamentInOrgOrThrow(organizationId, tournamentId);
        requireOrganizerAdmin(userId, organizationId);
        return requirementRepository.findByTournamentIdOrderByCreatedAtAsc(tournamentId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 参加チーム向け: 自チームが対象の提出枠のみを取得する（自チーム ADMIN/DEPUTY_ADMIN）。
     *
     * <p>他チームが対象の提出枠は返さない（情報開示の最小化）。ALL_TEAMS の枠は全チーム対象として返し、
     * SPECIFIC_TEAMS の枠は当該チームが {@code tournament_submission_requirement_target} に含まれる場合のみ返す。</p>
     *
     * @throws BusinessException SUBMISSION_REQ_VIEW_FORBIDDEN（403）／TOURNAMENT_NOT_FOUND（404）
     */
    public List<SubmissionRequirementResponse> listRequirementsForTeam(Long organizationId, Long tournamentId,
                                                                       Long teamId, Long userId) {
        findTournamentInOrgOrThrow(organizationId, tournamentId);
        requireTeamRepresentative(userId, teamId, TournamentErrorCode.SUBMISSION_REQ_VIEW_FORBIDDEN);
        return requirementRepository.findByTournamentIdOrderByCreatedAtAsc(tournamentId).stream()
                .filter(req -> isTeamTargeted(req, teamId))
                .map(this::toResponse)
                .toList();
    }

    /**
     * 提出枠を更新する（主催組織 ADMIN / SYSTEM_ADMIN）。締切・対象・支払い条件・表示情報。
     *
     * @throws BusinessException SUBMISSION_REQ_MANAGE_FORBIDDEN（403）／SUBMISSION_REQ_NOT_FOUND（404）／
     *                           DIVISION_NOT_FOUND（404）
     */
    @Transactional
    public SubmissionRequirementResponse updateRequirement(Long organizationId, Long tournamentId, UUID requirementId,
                                                           Long userId, UpdateSubmissionRequirementRequest request) {
        findTournamentInOrgOrThrow(organizationId, tournamentId);
        requireOrganizerAdmin(userId, organizationId);
        TournamentSubmissionRequirementEntity req = findRequirementInScopeOrThrow(requirementId, organizationId,
                tournamentId);

        if (request.getDivisionId() != null) {
            verifyDivisionBelongsToTournament(request.getDivisionId(), tournamentId);
        }

        SubmissionTargetScope newScope = request.getTargetScope() != null
                ? SubmissionTargetScope.valueOf(request.getTargetScope())
                : req.getTargetScope();

        req.update(request.getTitle(), request.getDescription(), request.getDivisionId(),
                newScope, request.getDeadline(), request.getRequiresPayment());
        requirementRepository.save(req);

        // 対象チーム明細の入れ替え（SPECIFIC_TEAMS のときのみ意味を持つ）
        List<Long> targetTeamIds;
        if (newScope == SubmissionTargetScope.SPECIFIC_TEAMS) {
            targetRepository.deleteByRequirementId(req.getId());
            targetTeamIds = persistTargets(req.getId(), newScope, request.getTeamIds());
        } else {
            // ALL_TEAMS に切り替えた場合は明細を消す
            targetRepository.deleteByRequirementId(req.getId());
            targetTeamIds = List.of();
        }

        log.info("提出枠更新: requirementId={}, scope={}", requirementId, newScope);
        return SubmissionRequirementResponse.of(req, targetTeamIds);
    }

    /**
     * 提出枠を論理削除する（主催組織 ADMIN / SYSTEM_ADMIN）。対象チーム明細も連鎖削除する。
     *
     * @throws BusinessException SUBMISSION_REQ_MANAGE_FORBIDDEN（403）／SUBMISSION_REQ_NOT_FOUND（404）
     */
    @Transactional
    public void deleteRequirement(Long organizationId, Long tournamentId, UUID requirementId, Long userId) {
        findTournamentInOrgOrThrow(organizationId, tournamentId);
        requireOrganizerAdmin(userId, organizationId);
        TournamentSubmissionRequirementEntity req = findRequirementInScopeOrThrow(requirementId, organizationId,
                tournamentId);

        targetRepository.deleteByRequirementId(req.getId());
        req.softDelete();
        requirementRepository.save(req);
        log.info("提出枠削除: requirementId={}", requirementId);
    }

    // ========================================================================
    // 提出状況ダッシュボード（主催組織 ADMIN）
    // ========================================================================

    /**
     * 提出枠の提出状況ダッシュボードを取得する（主催組織 ADMIN / SYSTEM_ADMIN）。
     *
     * <p>対象チーム母集団（ALL_TEAMS=参加チーム全体 / SPECIFIC_TEAMS=指定チーム）に対し、各チームの
     * 提出状況（未提出/提出済/受理/差戻し）と締切超過フラグを返す。提出の引き当ては
     * {@code form_submissions.tournament_submission_requirement_id} ＋ {@code scope_id(teamId)} で行う（設計書 §5）。</p>
     *
     * @throws BusinessException SUBMISSION_REQ_MANAGE_FORBIDDEN（403）／SUBMISSION_REQ_NOT_FOUND（404）
     */
    public SubmissionStatusDashboardResponse getStatusDashboard(Long organizationId, Long tournamentId,
                                                                UUID requirementId, Long userId) {
        findTournamentInOrgOrThrow(organizationId, tournamentId);
        requireOrganizerAdmin(userId, organizationId);
        TournamentSubmissionRequirementEntity req = findRequirementInScopeOrThrow(requirementId, organizationId,
                tournamentId);

        // 対象チーム母集団を解決
        List<Long> targetTeamIds = resolveTargetTeamIds(req);

        // requirement に紐づく全提出を team(scopeId) → 提出 にマップ（同一チーム複数提出は最新を採用）
        Map<Long, FormSubmissionEntity> byTeam = new LinkedHashMap<>();
        for (FormSubmissionEntity s : formSubmissionRepository.findByTournamentSubmissionRequirementId(requirementId)) {
            byTeam.merge(s.getScopeId(), s, (oldS, newS) ->
                    newS.getCreatedAt() != null && (oldS.getCreatedAt() == null
                            || newS.getCreatedAt().isAfter(oldS.getCreatedAt())) ? newS : oldS);
        }

        List<TeamSubmissionStatus> teams = new ArrayList<>();
        int notSubmitted = 0;
        int submitted = 0;
        int approved = 0;
        int returned = 0;
        for (Long teamId : targetTeamIds) {
            FormSubmissionEntity s = byTeam.get(teamId);
            if (s == null || s.getStatus() == SubmissionStatus.DRAFT) {
                notSubmitted++;
                String status = (s == null) ? "NOT_SUBMITTED" : SubmissionStatus.DRAFT.name();
                teams.add(new TeamSubmissionStatus(teamId, status,
                        s == null ? null : s.getId(), s == null ? null : s.getCreatedAt()));
                continue;
            }
            switch (s.getStatus()) {
                case SUBMITTED -> submitted++;
                case APPROVED -> approved++;
                case REJECTED, RETURNED -> returned++;
                default -> { /* DRAFT は上で処理済み */ }
            }
            teams.add(new TeamSubmissionStatus(teamId, s.getStatus().name(), s.getId(), s.getCreatedAt()));
        }

        return new SubmissionStatusDashboardResponse(
                requirementId,
                req.getDeadline(),
                req.isDeadlinePassed(),
                req.getTargetScope().name(),
                targetTeamIds.size(),
                notSubmitted,
                submitted,
                approved,
                returned,
                teams);
    }

    // ========================================================================
    // 自チーム分の提出（自チーム ADMIN/DEPUTY_ADMIN）
    // ========================================================================

    /**
     * 自チーム分の書類を提出する（自チーム ADMIN/DEPUTY_ADMIN のみ）。
     *
     * <p>実保存は F05.6 の {@link FormSubmissionService#createSubmissionForRequirement} に委譲し、
     * 本メソッドは「提出枠の帰属検証」「提出者＝対象チーム代表」「締切超過」「requires_payment ゲート」の
     * 認可・前提条件チェックのみを担う。提出は {@code form_submissions.tournament_submission_requirement_id} で
     * requirement と連結される。</p>
     *
     * @throws BusinessException SUBMISSION_REQ_NOT_FOUND（404）／SUBMISSION_SUBMIT_FORBIDDEN（403・他チーム代表でない）／
     *                           SUBMISSION_TEAM_NOT_TARGET（403・SPECIFIC_TEAMS の対象外）／
     *                           SUBMISSION_DEADLINE_PASSED（締切超過）／SUBMISSION_PAYMENT_REQUIRED（未払いゲート）
     */
    @Transactional
    // TODO: 原則5 — tournament ドメインの本メソッドが forms ドメインの FormSubmissionService を介して
    // form_submissions を更新する（ドメイン越境）。読み取り主体で結合度が低いため当面は直接委譲とし、
    // 将来は TournamentSubmissionCreatedEvent によるイベント駆動化を検討する。
    public FormSubmissionResponse submitForTeam(Long organizationId, Long tournamentId, UUID requirementId,
                                                Long teamId, Long userId, CreateFormSubmissionRequest request) {
        findTournamentInOrgOrThrow(organizationId, tournamentId);
        TournamentSubmissionRequirementEntity req = findRequirementInScopeOrThrow(requirementId, organizationId,
                tournamentId);

        // 提出者＝対象チームの代表（ADMIN/DEPUTY_ADMIN）であること
        requireTeamRepresentative(userId, teamId, TournamentErrorCode.SUBMISSION_SUBMIT_FORBIDDEN);
        // SPECIFIC_TEAMS のときは対象チームであること
        requireTeamIsTarget(req, teamId);
        // 締切超過チェック
        if (req.isDeadlinePassed()) {
            throw new BusinessException(TournamentErrorCode.SUBMISSION_DEADLINE_PASSED);
        }
        // requires_payment ゲート（領域⑦連携・未払いは提出をブロック＝症状を隠さず根治）
        if (req.isRequiresPayment()
                && !tournamentFeeService.isTeamPaidForTournament(tournamentId, req.getDivisionId(), teamId)) {
            throw new BusinessException(TournamentErrorCode.SUBMISSION_PAYMENT_REQUIRED);
        }
        // 提出枠が指す form_template と提出リクエストの template_id が一致すること（取り違え防止）
        if (!req.getFormTemplateId().equals(request.getTemplateId())) {
            throw new BusinessException(TournamentErrorCode.SUBMISSION_TEMPLATE_SCOPE_MISMATCH);
        }

        return formSubmissionService.createSubmissionForRequirement(
                SUBMISSION_SCOPE_TYPE, teamId, userId, requirementId, request);
    }

    // ========================================================================
    // 内部ヘルパー
    // ========================================================================

    private SubmissionRequirementResponse toResponse(TournamentSubmissionRequirementEntity req) {
        List<Long> targetTeamIds = req.getTargetScope() == SubmissionTargetScope.SPECIFIC_TEAMS
                ? targetRepository.findByRequirementId(req.getId()).stream()
                        .map(TournamentSubmissionRequirementTargetEntity::getTeamId).toList()
                : List.of();
        return SubmissionRequirementResponse.of(req, targetTeamIds);
    }

    private List<Long> persistTargets(UUID requirementId, SubmissionTargetScope scope, List<Long> teamIds) {
        if (scope != SubmissionTargetScope.SPECIFIC_TEAMS || teamIds == null || teamIds.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = teamIds.stream().distinct().toList();
        for (Long teamId : distinct) {
            targetRepository.save(TournamentSubmissionRequirementTargetEntity.builder()
                    .requirementId(requirementId)
                    .teamId(teamId)
                    .build());
        }
        return distinct;
    }

    /**
     * 提出枠の対象チーム母集団を解決する。
     * SPECIFIC_TEAMS は明細テーブルから、ALL_TEAMS は大会（またはディビジョン）の参加チームから引く。
     */
    private List<Long> resolveTargetTeamIds(TournamentSubmissionRequirementEntity req) {
        if (req.getTargetScope() == SubmissionTargetScope.SPECIFIC_TEAMS) {
            return targetRepository.findByRequirementId(req.getId()).stream()
                    .map(TournamentSubmissionRequirementTargetEntity::getTeamId)
                    .toList();
        }
        if (req.getDivisionId() != null) {
            return participantRepository.findDistinctParticipantTeamIdsByDivisionId(req.getDivisionId());
        }
        return participantRepository.findDistinctParticipantTeamIdsByTournamentId(req.getTournamentId());
    }

    /**
     * 当該チームが提出枠の対象か（一覧フィルタ用）。
     * ALL_TEAMS は常に対象。SPECIFIC_TEAMS は明細に含まれる場合のみ対象。
     */
    private boolean isTeamTargeted(TournamentSubmissionRequirementEntity req, Long teamId) {
        if (req.getTargetScope() == SubmissionTargetScope.ALL_TEAMS) {
            return true;
        }
        return targetRepository.existsByRequirementIdAndTeamId(req.getId(), teamId);
    }

    private TournamentEntity findTournamentInOrgOrThrow(Long organizationId, Long tournamentId) {
        TournamentEntity tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND));
        // 他組織の大会は存在を隠して 404（IDOR 対策）
        if (!tournament.getOrganizationId().equals(organizationId)) {
            throw new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }
        return tournament;
    }

    private void verifyDivisionBelongsToTournament(Long divisionId, Long tournamentId) {
        TournamentDivisionEntity division = divisionRepository.findById(divisionId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.DIVISION_NOT_FOUND));
        if (!division.getTournamentId().equals(tournamentId)) {
            throw new BusinessException(TournamentErrorCode.DIVISION_NOT_FOUND);
        }
    }

    private void requireOrganizationTemplate(Long formTemplateId, Long organizationId) {
        FormTemplateEntity template = formTemplateService.getTemplateEntity(formTemplateId);
        // 提出枠用テンプレートは主催組織スコープであること（クロス組織/別チームの流用を防ぐ）
        if (!"ORGANIZATION".equals(template.getScopeType())
                || !organizationId.equals(template.getScopeId())) {
            throw new BusinessException(TournamentErrorCode.SUBMISSION_TEMPLATE_SCOPE_MISMATCH);
        }
    }

    private TournamentSubmissionRequirementEntity findRequirementInScopeOrThrow(UUID requirementId,
                                                                                Long organizationId,
                                                                                Long tournamentId) {
        TournamentSubmissionRequirementEntity req = requirementRepository
                .findByIdAndOrganizationId(requirementId, organizationId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.SUBMISSION_REQ_NOT_FOUND));
        if (!req.getTournamentId().equals(tournamentId)) {
            throw new BusinessException(TournamentErrorCode.SUBMISSION_REQ_NOT_FOUND);
        }
        return req;
    }

    private void requireOrganizerAdmin(Long userId, Long organizationId) {
        if (userId != null && accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (userId != null && accessControlService.isAdmin(userId, organizationId, "ORGANIZATION")) {
            return;
        }
        throw new BusinessException(TournamentErrorCode.SUBMISSION_REQ_MANAGE_FORBIDDEN);
    }

    private void requireTeamRepresentative(Long userId, Long teamId, TournamentErrorCode forbiddenCode) {
        if (userId == null || !accessControlService.isAdminOrAbove(userId, teamId, "TEAM")) {
            throw new BusinessException(forbiddenCode);
        }
    }

    private void requireTeamIsTarget(TournamentSubmissionRequirementEntity req, Long teamId) {
        if (req.getTargetScope() == SubmissionTargetScope.SPECIFIC_TEAMS
                && !targetRepository.existsByRequirementIdAndTeamId(req.getId(), teamId)) {
            throw new BusinessException(TournamentErrorCode.SUBMISSION_TEAM_NOT_TARGET);
        }
    }
}
