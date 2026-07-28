package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.tournament.LeagueRoundType;
import com.mannschaft.app.tournament.ParticipantStatus;
import com.mannschaft.app.tournament.StatAggregationType;
import com.mannschaft.app.tournament.StatDataType;
import com.mannschaft.app.tournament.TiebreakerCriteria;
import com.mannschaft.app.tournament.TiebreakerDirection;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.TournamentFormat;
import com.mannschaft.app.tournament.TournamentMapper;
import com.mannschaft.app.tournament.TournamentStatus;
import com.mannschaft.app.tournament.TournamentVisibility;
import com.mannschaft.app.tournament.dto.CreateTournamentRequest;
import com.mannschaft.app.tournament.dto.StatDefResponse;
import com.mannschaft.app.tournament.dto.TiebreakerResponse;
import com.mannschaft.app.tournament.dto.TournamentResponse;
import com.mannschaft.app.tournament.dto.UpdateTournamentRequest;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.entity.TournamentStatDefEntity;
import com.mannschaft.app.tournament.entity.TournamentTemplateEntity;
import com.mannschaft.app.tournament.entity.TournamentTiebreakerEntity;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.repository.TournamentStatDefRepository;
import com.mannschaft.app.tournament.repository.TournamentTemplateRepository;
import com.mannschaft.app.tournament.repository.TournamentTemplateStatDefRepository;
import com.mannschaft.app.tournament.repository.TournamentTemplateTiebreakerRepository;
import com.mannschaft.app.tournament.repository.TournamentTiebreakerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 大会・リーグ管理サービス。CRUD・ステータス管理・シーズン継続を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TournamentService {

    private final TournamentRepository tournamentRepository;
    private final TournamentTiebreakerRepository tiebreakerRepository;
    private final TournamentStatDefRepository statDefRepository;
    private final TournamentTemplateRepository templateRepository;
    private final TournamentTemplateTiebreakerRepository templateTiebreakerRepository;
    private final TournamentTemplateStatDefRepository templateStatDefRepository;
    private final TournamentDivisionRepository divisionRepository;
    private final TournamentParticipantRepository participantRepository;
    private final TournamentMapper mapper;
    private final ContentVisibilityChecker contentVisibilityChecker;
    /** 認可根治戦役 Wave7: 大会一覧/詳細で主催組織 ADMIN/DEPUTY_ADMIN を判定するため。 */
    private final com.mannschaft.app.common.AccessControlService accessControlService;
    /**
     * F08.7.1 連絡機能: 大会作成・シーズン継続時に連絡スペース（掲示板＋チャット）を自動払い出しする。
     * TODO: tournament ドメインから chat/bulletin ドメインを直接呼ぶ越境（原則5）。
     *       将来は TournamentCreatedEvent によるイベント駆動化候補。
     */
    private final TournamentContactSpaceProvisioningService contactSpaceProvisioningService;
    /**
     * F08.7.1 / 04 ファイル置き場: 大会作成・シーズン継続時にデフォルトフォルダ（「大会要項」）を自動付帯する。
     * TODO: tournament ドメインから filesharing ドメインを直接呼ぶ越境（原則5）。
     *       将来は TournamentCreatedEvent によるイベント駆動化候補。
     */
    private final com.mannschaft.app.filesharing.service.SharedFolderService sharedFolderService;

    /** F08.7.1 / 04: 大会スコープのデフォルトフォルダ名。 */
    private static final String DEFAULT_TOURNAMENT_FOLDER = "大会要項";
    /** F08.7.1 / 04: ディビジョンスコープのデフォルトフォルダ名。 */
    private static final String DEFAULT_DIVISION_FOLDER = "規約";

    /**
     * 大会一覧を取得する（閲覧者の可視性でフィルタする）。
     *
     * <p>認可根治戦役 Wave7: 従来は {@code findByOrganizationId...} のみで可視性条件が無く、
     * 任意組織の全大会（DRAFT / 非公開含む）を一覧できる状態だった。
     * {@link StandingsQueryService} の per-tournament 可視性フィルタ（B-2b）と同方針で、
     * 取得したページの各大会を F00 共通可視性 Resolver で判定して除外する。</p>
     *
     * <p><b>主催組織 ADMIN/DEPUTY_ADMIN は自組織の全大会を閲覧できる</b>（DRAFT 含む）。
     * {@code TournamentVisibilityResolver} は DRAFT を「作成者と SystemAdmin のみ可視」と判定するため、
     * 可視性フィルタだけだと「別の管理者が作成した DRAFT 大会が管理画面から消える」機能退行が起きる。
     * 本メソッドの結果集合は {@code organization_id = orgId} で構成されるため、
     * パス {@code orgId} を管理者判定の scope に用いても BOLA にはならない。</p>
     *
     * <p>可視性フィルタはページ取得後に適用するため、{@code totalElements} は当該ページで
     * 除外した件数ぶんを差し引いた近似値になる（全件走査を避けるための意図的な割り切り）。</p>
     *
     * @param viewerUserId 閲覧者 user_id（未認証は {@code null}）
     */
    public Page<TournamentResponse> listTournaments(Long orgId, String status, Pageable pageable,
                                                    Long viewerUserId) {
        Page<TournamentEntity> page = status != null
                ? tournamentRepository.findByOrganizationIdAndStatusOrderByCreatedAtDesc(
                        orgId, TournamentStatus.valueOf(status), pageable)
                : tournamentRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId, pageable);

        boolean orgManager = viewerUserId != null
                && (accessControlService.isSystemAdmin(viewerUserId)
                    || accessControlService.isAdminOrAbove(viewerUserId, orgId, "ORGANIZATION"));
        if (orgManager) {
            return page.map(mapper::toTournamentSummaryResponse);
        }

        Set<Long> visibleIds = contentVisibilityChecker.filterAccessible(
                ReferenceType.TOURNAMENT,
                page.getContent().stream().map(TournamentEntity::getId).toList(),
                viewerUserId);
        List<TournamentResponse> visible = page.getContent().stream()
                .filter(t -> visibleIds.contains(t.getId()))
                .map(mapper::toTournamentSummaryResponse)
                .toList();
        long excluded = (long) page.getNumberOfElements() - visible.size();
        return new PageImpl<>(visible, pageable, page.getTotalElements() - excluded);
    }

    /**
     * 公開大会一覧を取得する。
     *
     * <p>F00 Phase E-2 正規化: 旧実装は {@code status != DRAFT} で絞っていたが、
     * CANCELLED / ARCHIVED の PUBLIC 大会も返してしまうバグがあった。
     * {@link com.mannschaft.app.common.visibility.mapping.TournamentStatusMapper} の
     * PUBLISHED 区分（OPEN / IN_PROGRESS / COMPLETED）のみを対象にすることで、
     * {@link com.mannschaft.app.tournament.visibility.TournamentVisibilityResolver} の
     * 判定ロジック（PUBLIC × PUBLISHED → 全員閲覧可）と完全一致させる。</p>
     */
    public Page<TournamentResponse> listPublicTournaments(Long orgId, Pageable pageable) {
        return tournamentRepository.findByOrganizationIdAndVisibilityAndStatusInOrderByCreatedAtDesc(
                orgId, TournamentVisibility.PUBLIC,
                Set.of(TournamentStatus.OPEN, TournamentStatus.IN_PROGRESS, TournamentStatus.COMPLETED),
                pageable)
                .map(mapper::toTournamentSummaryResponse);
    }

    /**
     * 大会詳細を取得する（org 束縛＋閲覧者の可視性を検証する）。
     *
     * <p>認可根治戦役 Wave7: 従来は {@code orgId} 突合も可視性判定も無く、任意組織の
     * 非公開大会の詳細を取得できる状態だった。パス {@code orgId} と大会実体の
     * {@code organizationId} を突合し（不一致は 404 で存在秘匿）、そのうえで
     * {@code StandingsController} の {@code verifyTournamentVisible} と同じく
     * F00 共通可視性 Resolver で判定する。主催組織 ADMIN/DEPUTY_ADMIN は
     * 自組織の DRAFT 大会も閲覧できる（管理画面の機能退行を防ぐ。判定 scope は
     * <b>エンティティ由来</b>の {@code tournament.organizationId}）。</p>
     *
     * @param viewerUserId 閲覧者 user_id（未認証は {@code null}）
     */
    public TournamentResponse getTournament(Long orgId, Long tournamentId, Long viewerUserId) {
        TournamentEntity tournament = tournamentRepository.findById(tournamentId)
                .filter(t -> orgId.equals(t.getOrganizationId()))
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND));

        boolean orgManager = viewerUserId != null
                && (accessControlService.isSystemAdmin(viewerUserId)
                    || accessControlService.isAdminOrAbove(
                            viewerUserId, tournament.getOrganizationId(), "ORGANIZATION"));
        if (!orgManager
                && !contentVisibilityChecker.canView(
                        ReferenceType.TOURNAMENT, tournamentId, viewerUserId)) {
            throw new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }

        return buildTournamentResponse(tournament);
    }

    /**
     * 大会詳細レスポンスを組み立てる（<b>認可判定を含まない</b>内部専用）。
     *
     * <p>認可根治戦役 Wave7 / {@code feedback_authz_gate_on_public_entry_not_shared_method}:
     * 認可ゲートは公開入口である {@link #getTournament(Long, Long, Long)} にのみ置き、
     * 既に {@code @PreAuthorize("@accessGuard.isScopeAdmin(...)")} で認可済みの書込経路
     * （{@code createTournament} / {@code updateTournament} / {@code changeStatus} /
     * {@code continueTournament}）は本メソッドを使う。共有メソッドにゲートを埋めると、
     * 作成直後の DRAFT 大会（作成者以外の管理者には不可視）で自分の書込レスポンスが
     * 404 になる巻き添えが発生するため分離している。</p>
     */
    private TournamentResponse buildTournamentResponse(TournamentEntity tournament) {
        Long tournamentId = tournament.getId();
        List<TiebreakerResponse> tiebreakers = tiebreakerRepository
                .findByTournamentIdOrderByPriorityAsc(tournamentId)
                .stream().map(mapper::toTiebreakerResponse).toList();
        List<StatDefResponse> statDefs = statDefRepository
                .findByTournamentIdOrderBySortOrderAsc(tournamentId)
                .stream().map(mapper::toStatDefResponse).toList();
        return mapper.toTournamentResponse(tournament, tiebreakers, statDefs);
    }

    /**
     * 公開アクセス可能か検証する。指定組織に所属し、かつ匿名閲覧者として
     * F00 共通可視性 Resolver で閲覧可能であることを確認する。
     *
     * <p>F00 Phase C/E-2 正規化: {@link ContentVisibilityChecker#canView(ReferenceType, Long, Long)} に
     * {@code userId=null}（匿名）を渡し、Resolver の status × visibility 合成判定に委譲する。
     * これにより DRAFT / CANCELLED / ARCHIVED 等の status × PUBLIC が誤って通る既存バグも
     * 同時に塞がれる。</p>
     */
    public void verifyPublicAccess(Long orgId, Long tournamentId) {
        TournamentEntity tournament = findTournamentOrThrow(tournamentId);
        if (!tournament.getOrganizationId().equals(orgId)) {
            throw new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }
        if (!contentVisibilityChecker.canView(ReferenceType.TOURNAMENT, tournamentId, null)) {
            throw new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }
    }

    /**
     * divId が tournamentId 配下であることを束縛検証する（認可根治戦役 Wave2 トランシェ2C）。
     *
     * <p>公開大会（PUBLIC）の tId を踏み台に、非公開大会（MEMBERS_AND_ABOVE 等）の divId を
     * 閲覧できてしまう穴（台帳指摘）を閉塞する。{@link #verifyPublicAccess}（tId 単位の可視性）と
     * 併用し、公開/埋め込み系の divId 引数を持つ EP から必ず呼ぶこと。</p>
     *
     * @throws BusinessException DIVISION_NOT_FOUND（404・IDOR 対策で存在秘匿）
     */
    public void verifyDivisionInTournament(Long tournamentId, Long divId) {
        divisionRepository.findByIdAndTournamentId(divId, tournamentId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.DIVISION_NOT_FOUND));
    }

    /**
     * 公開大会詳細を取得する。visibility = PUBLIC のみ返却。
     */
    public TournamentResponse getPublicTournament(Long orgId, Long tournamentId) {
        verifyPublicAccess(orgId, tournamentId);
        TournamentEntity tournament = findTournamentOrThrow(tournamentId);
        List<TiebreakerResponse> tiebreakers = tiebreakerRepository
                .findByTournamentIdOrderByPriorityAsc(tournamentId)
                .stream().map(mapper::toTiebreakerResponse).toList();
        List<StatDefResponse> statDefs = statDefRepository
                .findByTournamentIdOrderBySortOrderAsc(tournamentId)
                .stream().map(mapper::toStatDefResponse).toList();
        return mapper.toTournamentResponse(tournament, tiebreakers, statDefs);
    }

    /**
     * 大会を作成する。
     */
    @Transactional
    public TournamentResponse createTournament(Long orgId, Long userId, CreateTournamentRequest request) {
        TournamentFormat format = TournamentFormat.valueOf(request.getFormat());

        TournamentEntity.TournamentEntityBuilder builder = TournamentEntity.builder()
                .organizationId(orgId)
                .templateId(request.getTemplateId())
                .name(request.getName())
                .description(request.getDescription())
                .format(format)
                // F08.10 多競技対応（🟡-1a）: 未指定は SOCCER 既定。検証は resolveSport で Sport.valueOf 相当。
                .sport(resolveSport(request.getSport()))
                .season(request.getSeason())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .createdBy(userId);

        // テンプレートがある場合は初期値をコピー
        if (request.getTemplateId() != null) {
            TournamentTemplateEntity template = templateRepository.findById(request.getTemplateId())
                    .orElseThrow(() -> new BusinessException(TournamentErrorCode.TEMPLATE_NOT_FOUND));
            builder.winPoints(template.getWinPoints())
                    .drawPoints(template.getDrawPoints())
                    .lossPoints(template.getLossPoints())
                    .hasDraw(template.getHasDraw())
                    .hasSets(template.getHasSets())
                    .setsToWin(template.getSetsToWin())
                    .hasExtraTime(template.getHasExtraTime())
                    .hasPenalties(template.getHasPenalties())
                    .scoreUnitLabel(template.getScoreUnitLabel())
                    .bonusPointRules(template.getBonusPointRules());
        }

        // リクエストの値で上書き
        if (request.getWinPoints() != null) builder.winPoints(request.getWinPoints());
        if (request.getDrawPoints() != null) builder.drawPoints(request.getDrawPoints());
        if (request.getLossPoints() != null) builder.lossPoints(request.getLossPoints());
        if (request.getHasDraw() != null) builder.hasDraw(request.getHasDraw());
        if (request.getHasSets() != null) builder.hasSets(request.getHasSets());
        if (request.getSetsToWin() != null) builder.setsToWin(request.getSetsToWin());
        if (request.getHasExtraTime() != null) builder.hasExtraTime(request.getHasExtraTime());
        if (request.getHasPenalties() != null) builder.hasPenalties(request.getHasPenalties());
        if (request.getScoreUnitLabel() != null) builder.scoreUnitLabel(request.getScoreUnitLabel());
        if (request.getBonusPointRules() != null) builder.bonusPointRules(request.getBonusPointRules());
        if (request.getLeagueRoundType() != null)
            builder.leagueRoundType(LeagueRoundType.valueOf(request.getLeagueRoundType()));
        if (request.getKnockoutLegs() != null) builder.knockoutLegs(request.getKnockoutLegs());
        if (request.getVisibility() != null)
            builder.visibility(TournamentVisibility.valueOf(request.getVisibility()));

        TournamentEntity tournament = tournamentRepository.save(builder.build());
        Long tournamentId = tournament.getId();

        // テンプレートからタイブレーク・成績項目をディープコピー
        if (request.getTemplateId() != null) {
            copyTiebreakersFromTemplate(tournamentId, request.getTemplateId());
            copyStatDefsFromTemplate(tournamentId, request.getTemplateId());
        }

        // リクエストに明示的にタイブレーク・成績項目が含まれていれば上書き
        if (request.getTiebreakers() != null && !request.getTiebreakers().isEmpty()) {
            tiebreakerRepository.deleteByTournamentId(tournamentId);
            saveTournamentTiebreakers(tournamentId, request.getTiebreakers());
        }
        if (request.getStatDefs() != null && !request.getStatDefs().isEmpty()) {
            statDefRepository.deleteByTournamentId(tournamentId);
            saveTournamentStatDefs(tournamentId, request.getStatDefs());
        }

        // F08.7.1: 大会全体の連絡スペース（掲示板＋チャット）を自動付帯（要件④）
        contactSpaceProvisioningService.provisionForTournament(tournamentId, tournament.getName());
        // F08.7.1 / 04: 大会スコープのデフォルトフォルダ「大会要項」を自動付帯（冪等・§4）
        sharedFolderService.provisionDefaultFolder(
                com.mannschaft.app.filesharing.FileScopeType.TOURNAMENT,
                orgId, tournamentId, userId, DEFAULT_TOURNAMENT_FOLDER);

        // 認可済み書込経路のため、可視性ゲートを持たない内部組立に委ねる（DRAFT 自己閲覧の巻き添え回避）
        return buildTournamentResponse(findTournamentOrThrow(tournamentId));
    }

    /**
     * 大会を更新する。
     */
    @Transactional
    public TournamentResponse updateTournament(Long tournamentId, UpdateTournamentRequest request) {
        TournamentEntity tournament = findTournamentOrThrow(tournamentId);
        tournament.update(
                request.getName() != null ? request.getName() : tournament.getName(),
                request.getDescription() != null ? request.getDescription() : tournament.getDescription(),
                request.getFormat() != null ? TournamentFormat.valueOf(request.getFormat()) : tournament.getFormat(),
                // F08.10 多競技対応（🟡-1a）: 未指定は既存値維持。指定時は resolveSport で検証。
                request.getSport() != null ? resolveSport(request.getSport()) : tournament.getSport(),
                request.getSeason() != null ? request.getSeason() : tournament.getSeason(),
                request.getStartDate() != null ? request.getStartDate() : tournament.getStartDate(),
                request.getEndDate() != null ? request.getEndDate() : tournament.getEndDate(),
                request.getWinPoints() != null ? request.getWinPoints() : tournament.getWinPoints(),
                request.getDrawPoints() != null ? request.getDrawPoints() : tournament.getDrawPoints(),
                request.getLossPoints() != null ? request.getLossPoints() : tournament.getLossPoints(),
                request.getHasDraw() != null ? request.getHasDraw() : tournament.getHasDraw(),
                request.getHasSets() != null ? request.getHasSets() : tournament.getHasSets(),
                request.getSetsToWin() != null ? request.getSetsToWin() : tournament.getSetsToWin(),
                request.getHasExtraTime() != null ? request.getHasExtraTime() : tournament.getHasExtraTime(),
                request.getHasPenalties() != null ? request.getHasPenalties() : tournament.getHasPenalties(),
                request.getScoreUnitLabel() != null ? request.getScoreUnitLabel() : tournament.getScoreUnitLabel(),
                request.getBonusPointRules() != null ? request.getBonusPointRules() : tournament.getBonusPointRules(),
                request.getLeagueRoundType() != null ? LeagueRoundType.valueOf(request.getLeagueRoundType()) : tournament.getLeagueRoundType(),
                request.getKnockoutLegs() != null ? request.getKnockoutLegs() : tournament.getKnockoutLegs(),
                request.getVisibility() != null ? TournamentVisibility.valueOf(request.getVisibility()) : tournament.getVisibility());
        tournamentRepository.save(tournament);

        if (request.getTiebreakers() != null) {
            tiebreakerRepository.deleteByTournamentId(tournamentId);
            saveTournamentTiebreakers(tournamentId, request.getTiebreakers());
        }
        if (request.getStatDefs() != null) {
            statDefRepository.deleteByTournamentId(tournamentId);
            saveTournamentStatDefs(tournamentId, request.getStatDefs());
        }

        // 認可済み書込経路のため、可視性ゲートを持たない内部組立に委ねる（DRAFT 自己閲覧の巻き添え回避）
        return buildTournamentResponse(findTournamentOrThrow(tournamentId));
    }

    /**
     * 大会を論理削除する。
     */
    @Transactional
    public void deleteTournament(Long tournamentId) {
        TournamentEntity tournament = findTournamentOrThrow(tournamentId);
        // F08.7.1 §6.1: 連絡スペースを archive（履歴保持・クロスドメインCASCADEなし・原則2）
        contactSpaceProvisioningService.archiveForTournament(tournamentId);
        tournament.softDelete();
        tournamentRepository.save(tournament);
    }

    /**
     * 大会ステータスを変更する。
     */
    @Transactional
    public TournamentResponse changeStatus(Long tournamentId, TournamentStatus newStatus) {
        TournamentEntity tournament = findTournamentOrThrow(tournamentId);
        // OPEN → IN_PROGRESS の場合、全参加チームを ACTIVE に変更
        if (tournament.getStatus() == TournamentStatus.OPEN && newStatus == TournamentStatus.IN_PROGRESS) {
            List<TournamentDivisionEntity> divisions =
                    divisionRepository.findByTournamentIdOrderByLevelAscSortOrderAsc(tournamentId);
            for (TournamentDivisionEntity div : divisions) {
                List<TournamentParticipantEntity> participants =
                        participantRepository.findByDivisionIdAndStatus(div.getId(), ParticipantStatus.REGISTERED);
                participants.forEach(p -> p.changeStatus(ParticipantStatus.ACTIVE));
                participantRepository.saveAll(participants);
            }
        }
        tournament.changeStatus(newStatus);
        tournamentRepository.save(tournament);
        // 認可済み書込経路のため、可視性ゲートを持たない内部組立に委ねる（DRAFT 自己閲覧の巻き添え回避）
        return buildTournamentResponse(findTournamentOrThrow(tournamentId));
    }

    /**
     * 前シーズンから継続して大会を作成する。
     */
    @Transactional
    public TournamentResponse continueTournament(Long orgId, Long userId, Long previousTournamentId) {
        TournamentEntity previous = findTournamentOrThrow(previousTournamentId);
        if (previous.getStatus() != TournamentStatus.COMPLETED &&
            previous.getStatus() != TournamentStatus.ARCHIVED) {
            throw new BusinessException(TournamentErrorCode.INVALID_TOURNAMENT_STATUS);
        }

        // 大会のルール値をコピー
        TournamentEntity newTournament = TournamentEntity.builder()
                .organizationId(orgId)
                .templateId(previous.getTemplateId())
                .previousTournamentId(previousTournamentId)
                .name(previous.getName())
                .description(previous.getDescription())
                .format(previous.getFormat())
                // F08.10 多競技対応（🟡-1a）: シーズン継続では旧大会の競技を引き継ぐ。
                .sport(previous.getSport())
                .winPoints(previous.getWinPoints())
                .drawPoints(previous.getDrawPoints())
                .lossPoints(previous.getLossPoints())
                .hasDraw(previous.getHasDraw())
                .hasSets(previous.getHasSets())
                .setsToWin(previous.getSetsToWin())
                .hasExtraTime(previous.getHasExtraTime())
                .hasPenalties(previous.getHasPenalties())
                .scoreUnitLabel(previous.getScoreUnitLabel())
                .bonusPointRules(previous.getBonusPointRules())
                .leagueRoundType(previous.getLeagueRoundType())
                .knockoutLegs(previous.getKnockoutLegs())
                .visibility(previous.getVisibility())
                .createdBy(userId)
                .build();
        newTournament = tournamentRepository.save(newTournament);
        Long newTournamentId = newTournament.getId();

        // タイブレーク・成績項目をコピー
        tiebreakerRepository.findByTournamentIdOrderByPriorityAsc(previousTournamentId)
                .forEach(tb -> tiebreakerRepository.save(TournamentTiebreakerEntity.builder()
                        .tournamentId(newTournamentId)
                        .priority(tb.getPriority())
                        .criteria(tb.getCriteria())
                        .direction(tb.getDirection())
                        .build()));
        statDefRepository.findByTournamentIdOrderBySortOrderAsc(previousTournamentId)
                .forEach(sd -> statDefRepository.save(TournamentStatDefEntity.builder()
                        .tournamentId(newTournamentId)
                        .name(sd.getName())
                        .statKey(sd.getStatKey())
                        .unit(sd.getUnit())
                        .dataType(sd.getDataType())
                        .aggregationType(sd.getAggregationType())
                        .isRankingTarget(sd.getIsRankingTarget())
                        .rankingLabel(sd.getRankingLabel())
                        .sortOrder(sd.getSortOrder())
                        .build()));

        // F08.7.1: 新シーズンの大会全体スペースを払い出す（要件④）
        contactSpaceProvisioningService.provisionForTournament(newTournamentId, newTournament.getName());
        // F08.7.1 / 04: 新シーズンの大会スコープにもデフォルトフォルダを払い出す（払い出し漏れ防止・§4）
        sharedFolderService.provisionDefaultFolder(
                com.mannschaft.app.filesharing.FileScopeType.TOURNAMENT,
                orgId, newTournamentId, userId, DEFAULT_TOURNAMENT_FOLDER);

        // ディビジョン構成をコピー
        List<TournamentDivisionEntity> prevDivisions =
                divisionRepository.findByTournamentIdOrderByLevelAscSortOrderAsc(previousTournamentId);
        for (TournamentDivisionEntity prevDiv : prevDivisions) {
            TournamentDivisionEntity newDiv = divisionRepository.save(TournamentDivisionEntity.builder()
                    .tournamentId(newTournamentId)
                    .name(prevDiv.getName())
                    .level(prevDiv.getLevel())
                    .promotionSlots(prevDiv.getPromotionSlots())
                    .relegationSlots(prevDiv.getRelegationSlots())
                    .playoffPromotionSlots(prevDiv.getPlayoffPromotionSlots())
                    .maxParticipants(prevDiv.getMaxParticipants())
                    .minEntryCount(prevDiv.getMinEntryCount())
                    .maxEntryCount(prevDiv.getMaxEntryCount())
                    .sortOrder(prevDiv.getSortOrder())
                    .build());
            // F08.7.1: 複製ディビジョンにも連絡スペースを払い出す（払い出し漏れ防止・§3.3）
            contactSpaceProvisioningService.provisionForDivision(
                    newDiv.getId(), newTournament.getName() + " " + newDiv.getName() + " 連絡");
            // F08.7.1 / 04: 複製ディビジョンにもデフォルトフォルダ「規約」を払い出す（払い出し漏れ防止・§4）
            sharedFolderService.provisionDefaultFolder(
                    com.mannschaft.app.filesharing.FileScopeType.TOURNAMENT_DIVISION,
                    orgId, newDiv.getId(), userId, DEFAULT_DIVISION_FOLDER);
        }

        // 認可済み書込経路のため、可視性ゲートを持たない内部組立に委ねる（DRAFT 自己閲覧の巻き添え回避）
        return buildTournamentResponse(findTournamentOrThrow(newTournamentId));
    }

    TournamentEntity findTournamentOrThrow(Long tournamentId) {
        return tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND));
    }

    /**
     * 競技種別文字列を検証し、保存用の正準名（{@code Sport} の列挙名）へ解決する（F08.10 多競技対応・🟡-1a）。
     *
     * <p>{@code null}（未指定）は後方互換のため {@code SOCCER} 既定とする。値が指定された場合は
     * {@code Sport.valueOf} 相当で妥当性を検証し、不正値は {@link IllegalArgumentException} を投げる
     * （DTO の {@code @Pattern} で 400 に変換済みだが、Service 単独呼び出し・将来の経路に対する多重防御）。</p>
     *
     * @param sport 競技種別の列挙名（null 可）
     * @return 正準化された競技種別の列挙名（保存値・String）
     */
    private String resolveSport(String sport) {
        if (sport == null) {
            return Sport.SOCCER.name();
        }
        // 不正値はここで弾く（症状を握りつぶさない・enum へ変換できることを保証）。
        return Sport.valueOf(sport).name();
    }

    private void copyTiebreakersFromTemplate(Long tournamentId, Long templateId) {
        templateTiebreakerRepository.findByTemplateIdOrderByPriorityAsc(templateId)
                .forEach(ttb -> tiebreakerRepository.save(
                        TournamentTiebreakerEntity.builder()
                                .tournamentId(tournamentId)
                                .priority(ttb.getPriority())
                                .criteria(ttb.getCriteria())
                                .direction(ttb.getDirection())
                                .build()));
    }

    private void copyStatDefsFromTemplate(Long tournamentId, Long templateId) {
        templateStatDefRepository.findByTemplateIdOrderBySortOrderAsc(templateId)
                .forEach(tsd -> statDefRepository.save(
                        TournamentStatDefEntity.builder()
                                .tournamentId(tournamentId)
                                .name(tsd.getName())
                                .statKey(tsd.getStatKey())
                                .unit(tsd.getUnit())
                                .dataType(tsd.getDataType())
                                .aggregationType(tsd.getAggregationType())
                                .isRankingTarget(tsd.getIsRankingTarget())
                                .rankingLabel(tsd.getRankingLabel())
                                .sortOrder(tsd.getSortOrder())
                                .build()));
    }

    private void saveTournamentTiebreakers(Long tournamentId,
                                           List<com.mannschaft.app.tournament.dto.TiebreakerRequest> requests) {
        requests.forEach(req -> tiebreakerRepository.save(
                TournamentTiebreakerEntity.builder()
                        .tournamentId(tournamentId)
                        .priority(req.getPriority())
                        .criteria(TiebreakerCriteria.valueOf(req.getCriteria()))
                        .direction(req.getDirection() != null
                                ? TiebreakerDirection.valueOf(req.getDirection())
                                : TiebreakerDirection.DESC)
                        .build()));
    }

    private void saveTournamentStatDefs(Long tournamentId,
                                        List<com.mannschaft.app.tournament.dto.StatDefRequest> requests) {
        requests.forEach(req -> statDefRepository.save(
                TournamentStatDefEntity.builder()
                        .tournamentId(tournamentId)
                        .name(req.getName())
                        .statKey(req.getStatKey())
                        .unit(req.getUnit())
                        .dataType(req.getDataType() != null
                                ? StatDataType.valueOf(req.getDataType())
                                : StatDataType.INTEGER)
                        .aggregationType(req.getAggregationType() != null
                                ? StatAggregationType.valueOf(req.getAggregationType())
                                : StatAggregationType.SUM)
                        .isRankingTarget(req.getIsRankingTarget() != null ? req.getIsRankingTarget() : true)
                        .rankingLabel(req.getRankingLabel())
                        .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                        .build()));
    }
}
