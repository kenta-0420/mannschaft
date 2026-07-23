package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.MeetupAttendanceResponse;
import com.mannschaft.app.village.dto.MeetupAttendanceUpsertRequest;
import com.mannschaft.app.village.dto.MeetupCandidateDateAddRequest;
import com.mannschaft.app.village.dto.MeetupCandidateDateInput;
import com.mannschaft.app.village.dto.MeetupCandidateDateResponse;
import com.mannschaft.app.village.dto.MeetupCommentCreateRequest;
import com.mannschaft.app.village.dto.MeetupCommentResponse;
import com.mannschaft.app.village.dto.MeetupConfirmRequest;
import com.mannschaft.app.village.dto.MeetupCreateRequest;
import com.mannschaft.app.village.dto.MeetupResponse;
import com.mannschaft.app.village.dto.MeetupTodoCreateRequest;
import com.mannschaft.app.village.dto.MeetupTodoResponse;
import com.mannschaft.app.village.dto.MeetupUpdateRequest;
import com.mannschaft.app.village.dto.MeetupVoteRequest;
import com.mannschaft.app.village.dto.MeetupVoteSummaryResponse;
import com.mannschaft.app.village.entity.UserVillageNicknameEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMeetupAttendanceEntity;
import com.mannschaft.app.village.entity.VillageMeetupCandidateDateEntity;
import com.mannschaft.app.village.entity.VillageMeetupCommentEntity;
import com.mannschaft.app.village.entity.VillageMeetupEntity;
import com.mannschaft.app.village.entity.VillageMeetupTodoEntity;
import com.mannschaft.app.village.entity.VillageMeetupVoteEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageEventNotificationType;
import com.mannschaft.app.village.entity.enums.VillageMeetupAttendanceStatus;
import com.mannschaft.app.village.entity.enums.VillageMeetupStatus;
import com.mannschaft.app.village.event.VillageEventOccurredEvent;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.UserVillageNicknameRepository;
import com.mannschaft.app.village.repository.VillageMeetupAttendanceRepository;
import com.mannschaft.app.village.repository.VillageMeetupCandidateDateRepository;
import com.mannschaft.app.village.repository.VillageMeetupCommentRepository;
import com.mannschaft.app.village.repository.VillageMeetupRepository;
import com.mannschaft.app.village.repository.VillageMeetupTodoRepository;
import com.mannschaft.app.village.repository.VillageMeetupVoteRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * F17.1 Phase 3-β — 寄合サービス。
 *
 * <p>村人同士のオフ会・集まりの日程調整を行う。候補日複数提示 → 投票 → 幹事が確定日決定。</p>
 *
 * <h2>権限</h2>
 * <ul>
 *   <li>作成: 当該村の村人なら誰でも可（VillageRole 問わず）。幹事 = 作成者。</li>
 *   <li>更新・確定・中止・候補日追加削除: 幹事のみ（organizer_user_id 一致）。</li>
 *   <li>投票: 当該村の村人のみ。</li>
 *   <li>一覧・詳細・集計: 当該村の村人のみ。</li>
 * </ul>
 *
 * <h2>原則準拠</h2>
 * <ul>
 *   <li>原則1: 幹事・投票者は user_id だけ保持し FK は張らない。</li>
 *   <li>原則5: {@code @Transactional} は village ドメイン内に閉じる。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VillageMeetupService {

    /** 一覧の最大ページサイズ。 */
    private static final int MAX_PAGE_SIZE = 100;

    /** F17.2 一覧（出欠・コメント・宿題）の既定ページサイズ（設計書 §13.5）。 */
    private static final int DEFAULT_LIST_PAGE_SIZE = 20;

    /** 候補日の最大件数（DTO の @Size と同期）。 */
    private static final int MAX_CANDIDATE_DATES = 30;

    private final VillageMeetupRepository meetupRepository;
    private final VillageMeetupCandidateDateRepository candidateDateRepository;
    private final VillageMeetupVoteRepository voteRepository;
    private final VillageRepository villageRepository;
    private final VillageMembershipRepository membershipRepository;
    private final AuditLogService auditLogService;
    // F17.2 Wave1 ②寄合後半戦
    private final VillageMeetupAttendanceRepository attendanceRepository;
    private final VillageMeetupCommentRepository commentRepository;
    private final VillageMeetupTodoRepository todoRepository;
    private final UserVillageNicknameRepository nicknameRepository;
    /** F17.2 Wave2 ①: 行事→村フィード自動還流イベントの発行（AFTER_COMMIT リスナーが購読・§3.3.1）。 */
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    // ====================================================================
    // 作成
    // ====================================================================

    /**
     * 寄合を作成する（当該村の村人なら誰でも可）。
     * 作成者が自動的に幹事となる。候補日は同時に登録する。
     */
    @Transactional
    public MeetupResponse createMeetup(UUID villageId, MeetupCreateRequest request, Long actorUserId) {
        loadActiveVillage(villageId);
        requireVillager(villageId, actorUserId);

        if (request.candidateDates() == null || request.candidateDates().isEmpty()) {
            throw new BusinessException(VillageErrorCode.VILLAGE_FIELD_INVALID);
        }
        if (request.candidateDates().size() > MAX_CANDIDATE_DATES) {
            throw new BusinessException(VillageErrorCode.VILLAGE_FIELD_INVALID);
        }
        // 候補日重複（同一リスト内）チェック。
        // MySQL の UNIQUE は TIME NULL を重複許容するため、DB 制約だけでは終日候補の重複を弾けない。
        // よって (date, time) ペアでアプリ層でも重複を検査する（#2357）。
        Set<CandidateDateKey> uniq = new HashSet<>();
        for (MeetupCandidateDateInput input : request.candidateDates()) {
            if (input == null || input.date() == null) {
                throw new BusinessException(VillageErrorCode.VILLAGE_FIELD_INVALID);
            }
            if (!uniq.add(new CandidateDateKey(input.date(), input.time()))) {
                throw new BusinessException(VillageErrorCode.VOTE_DUPLICATE);
            }
        }

        VillageMeetupEntity entity = VillageMeetupEntity.builder()
                .villageId(villageId)
                .title(request.title())
                .description(request.description())
                .organizerUserId(actorUserId)
                .status(VillageMeetupStatus.PLANNING)
                .location(request.location())
                // F17.2 追補: 定員をそのまま保存する最小結線（試練骨格）。
                // capacity>=1/null の下限バリデーション・満席強制は出陣フェーズで実装する。
                .capacity(request.capacity())
                .build();
        VillageMeetupEntity saved = meetupRepository.save(entity);

        // 候補日登録（日付 + 任意の時刻）
        int order = 0;
        for (MeetupCandidateDateInput input : request.candidateDates()) {
            VillageMeetupCandidateDateEntity cd = VillageMeetupCandidateDateEntity.builder()
                    .meetupId(saved.getId())
                    .candidateDate(input.date())
                    .candidateTime(input.time())
                    .sortOrder(order++)
                    .build();
            candidateDateRepository.save(cd);
        }

        auditLogService.record(
                AuditEventType.VILLAGE_MEETUP_CREATED.name(),
                actorUserId, null, null, null,
                null, null, null,
                "{\"villageId\":\"" + villageId
                        + "\",\"meetupId\":\"" + saved.getId()
                        + "\",\"candidateDates\":" + request.candidateDates().size() + "}"
        );
        log.info("Village meetup created: villageId={} meetupId={} candidates={} by userId={}",
                villageId, saved.getId(), request.candidateDates().size(), actorUserId);

        // F17.2 Wave2 ①: 行事作成の還流（EVENT_CREATED・本体コミット後に AFTER_COMMIT リスナーが発火・§3.3.1）。
        eventPublisher.publishEvent(new VillageEventOccurredEvent(
                villageId, VillageEventNotificationType.EVENT_CREATED, saved.getId(),
                saved.getTitle(), "/villages/" + villageId + "/meetups/" + saved.getId()));

        return buildResponseWithCandidates(saved);
    }

    // ====================================================================
    // 更新
    // ====================================================================

    /**
     * 寄合を更新する（幹事のみ）。
     * CONFIRMED / CANCELLED 状態は更新不可。
     */
    @Transactional
    public MeetupResponse updateMeetup(UUID villageId,
                                       UUID meetupId,
                                       MeetupUpdateRequest request,
                                       Long actorUserId) {
        loadActiveVillage(villageId);
        VillageMeetupEntity entity = loadMeetup(villageId, meetupId);

        // F17.2 Wave1 ②寄合後半戦（AC-13）: フィールド単位で認可・状態ガードを分ける。
        //  - 基本フィールド（title/description/location）: 従来どおり「幹事のみ・PLANNING のみ」。
        //  - decisions_note（決まったこと）: 「幹事＋村長/長老」が状態を問わず（CONFIRMED でも）更新できる。
        // 既存の幹事限定ガードを decisions_note の都合で他フィールドまで緩めない（殿の御下命）。
        boolean touchesCore = request.title() != null
                || request.description() != null
                || request.location() != null;
        boolean touchesDecisions = request.decisionsNote() != null;
        // F17.2 追補: capacity（定員）はフィールド単位で認可・状態ガードを分ける（decisions_note と同格）。
        //  - 編集権者 = 幹事＋村長/長老（requireOrganizerOrModerator）。
        //  - 状態は PLANNING/CONFIRMED 両可（CANCELLED のみ拒否・§4.5）。
        boolean touchesCapacity = request.capacity() != null;

        // どのフィールドも変更しない空更新は、後方互換のため従来の「幹事・PLANNING」ガードで扱う。
        // capacity/decisions のみの更新は core ガードに巻き込まない（各々専用の認可・状態ガードで扱う）。
        if (touchesCore || (!touchesDecisions && !touchesCapacity)) {
            requireOrganizer(entity, actorUserId);
            if (entity.getStatus() != VillageMeetupStatus.PLANNING) {
                throw new BusinessException(VillageErrorCode.MEETUP_INVALID_STATUS);
            }
        }
        if (touchesDecisions) {
            requireOrganizerOrModerator(villageId, entity, actorUserId);
            // CANCELLED は読み取りのみ（§4.5）。PLANNING/CONFIRMED は決まったこと更新可。
            rejectIfCancelled(entity);
            entity.setDecisionsNote(request.decisionsNote());
        }
        if (touchesCapacity) {
            requireOrganizerOrModerator(villageId, entity, actorUserId);
            rejectIfCancelled(entity);
            // 現 GOING 数より小さい定員へ縮小してもよい（既存 GOING はキックしない）。
            // 縮小後は remaining=0 となり、以後の新規 GOING が upsertAttendance で塞がれる。
            entity.setCapacity(request.capacity());
        }

        if (request.title() != null) {
            entity.setTitle(request.title());
        }
        if (request.description() != null) {
            entity.setDescription(request.description());
        }
        if (request.location() != null) {
            entity.setLocation(request.location());
        }

        VillageMeetupEntity saved = meetupRepository.save(entity);

        auditLogService.record(
                AuditEventType.VILLAGE_MEETUP_UPDATED.name(),
                actorUserId, null, null, null,
                null, null, null,
                "{\"villageId\":\"" + villageId
                        + "\",\"meetupId\":\"" + saved.getId() + "\"}"
        );
        log.info("Village meetup updated: villageId={} meetupId={} by userId={}",
                villageId, saved.getId(), actorUserId);

        return buildResponseWithCandidates(saved);
    }

    // ====================================================================
    // 中止
    // ====================================================================

    /**
     * 寄合を中止する（幹事のみ）。既に CANCELLED / CONFIRMED の場合は冪等 no-op。
     */
    @Transactional
    public MeetupResponse cancelMeetup(UUID villageId, UUID meetupId, Long actorUserId) {
        loadActiveVillage(villageId);
        VillageMeetupEntity entity = loadMeetup(villageId, meetupId);
        requireOrganizer(entity, actorUserId);

        if (entity.getStatus() == VillageMeetupStatus.CANCELLED) {
            return buildResponseWithCandidates(entity);
        }
        if (entity.getStatus() == VillageMeetupStatus.CONFIRMED) {
            // 確定済みは中止できない（仕様: 確定後の取り消しは別フロー）
            throw new BusinessException(VillageErrorCode.MEETUP_INVALID_STATUS);
        }

        entity.setStatus(VillageMeetupStatus.CANCELLED);
        VillageMeetupEntity saved = meetupRepository.save(entity);

        auditLogService.record(
                AuditEventType.VILLAGE_MEETUP_CANCELLED.name(),
                actorUserId, null, null, null,
                null, null, null,
                "{\"villageId\":\"" + villageId
                        + "\",\"meetupId\":\"" + saved.getId() + "\"}"
        );
        log.info("Village meetup cancelled: villageId={} meetupId={} by userId={}",
                villageId, saved.getId(), actorUserId);

        return buildResponseWithCandidates(saved);
    }

    // ====================================================================
    // 確定
    // ====================================================================

    /**
     * 寄合の日付を確定する（幹事のみ）。PLANNING のみ実行可。
     * 指定された candidateDateId の日付を confirmed_date に転記する。
     */
    @Transactional
    public MeetupResponse confirmMeetup(UUID villageId,
                                        UUID meetupId,
                                        MeetupConfirmRequest request,
                                        Long actorUserId) {
        loadActiveVillage(villageId);
        VillageMeetupEntity entity = loadMeetup(villageId, meetupId);
        requireOrganizer(entity, actorUserId);

        if (entity.getStatus() == VillageMeetupStatus.CONFIRMED) {
            throw new BusinessException(VillageErrorCode.MEETUP_ALREADY_CONFIRMED);
        }
        if (entity.getStatus() != VillageMeetupStatus.PLANNING) {
            throw new BusinessException(VillageErrorCode.MEETUP_INVALID_STATUS);
        }

        VillageMeetupCandidateDateEntity cd = candidateDateRepository.findById(request.candidateDateId())
                .orElseThrow(() -> new BusinessException(VillageErrorCode.CANDIDATE_DATE_NOT_FOUND));
        if (!meetupId.equals(cd.getMeetupId())) {
            // IDOR: 他寄合の候補日 ID を指定
            throw new BusinessException(VillageErrorCode.CANDIDATE_DATE_NOT_FOUND);
        }

        entity.setStatus(VillageMeetupStatus.CONFIRMED);
        entity.setConfirmedDate(cd.getCandidateDate());
        // 候補の時刻（終日なら null）も確定時刻へ転記する（#2357）
        entity.setConfirmedTime(cd.getCandidateTime());
        VillageMeetupEntity saved = meetupRepository.save(entity);

        auditLogService.record(
                AuditEventType.VILLAGE_MEETUP_CONFIRMED.name(),
                actorUserId, null, null, null,
                null, null, null,
                "{\"villageId\":\"" + villageId
                        + "\",\"meetupId\":\"" + saved.getId()
                        + "\",\"confirmedDate\":\"" + saved.getConfirmedDate() + "\"}"
        );
        log.info("Village meetup confirmed: villageId={} meetupId={} date={} by userId={}",
                villageId, saved.getId(), saved.getConfirmedDate(), actorUserId);

        // F17.2 Wave2 ①: 寄合確定の還流（MEETUP_CONFIRMED・本体コミット後に AFTER_COMMIT リスナーが発火・§3.3.1）。
        eventPublisher.publishEvent(new VillageEventOccurredEvent(
                villageId, VillageEventNotificationType.MEETUP_CONFIRMED, saved.getId(),
                saved.getTitle(), "/villages/" + villageId + "/meetups/" + saved.getId()));

        return buildResponseWithCandidates(saved);
    }

    // ====================================================================
    // 一覧 / 詳細
    // ====================================================================

    /**
     * 村の寄合一覧を取得する。
     */
    public List<MeetupResponse> listMeetups(UUID villageId, VillageMeetupStatus status,
                                            Long actorUserId, Pageable pageable) {
        loadActiveVillage(villageId);
        requireVillager(villageId, actorUserId);
        Pageable resolved = resolvePageable(pageable);
        Page<VillageMeetupEntity> page = (status == null)
                ? meetupRepository.findByVillageIdAndDeletedAtIsNull(villageId, resolved)
                : meetupRepository.findByVillageIdAndStatusAndDeletedAtIsNull(villageId, status, resolved);
        List<VillageMeetupEntity> content = page.getContent();
        if (content.isEmpty()) {
            return List.of();
        }
        // F17.2 追補: GOING 実数を GROUP BY で一括取得し N+1 を回避する（AC-19）。
        List<UUID> ids = content.stream().map(VillageMeetupEntity::getId).toList();
        Map<UUID, Long> goingByMeetup = attendanceRepository
                .countByMeetupIdInAndStatusGrouped(ids, VillageMeetupAttendanceStatus.GOING)
                .stream()
                .collect(Collectors.toMap(
                        VillageMeetupAttendanceRepository.MeetupAttendanceStatusCount::getMeetupId,
                        VillageMeetupAttendanceRepository.MeetupAttendanceStatusCount::getCount));
        return content.stream()
                // 一覧では候補日は省略（パフォーマンス）。goingCount はバッチ供給・件数 0 は 0 埋め。
                .map(m -> MeetupResponse.of(m, null, goingByMeetup.getOrDefault(m.getId(), 0L)))
                .toList();
    }

    /**
     * 寄合詳細を取得する（候補日込み）。
     */
    public MeetupResponse getMeetup(UUID villageId, UUID meetupId, Long actorUserId) {
        loadActiveVillage(villageId);
        requireVillager(villageId, actorUserId);
        VillageMeetupEntity entity = loadMeetup(villageId, meetupId);
        return buildResponseWithCandidates(entity);
    }

    // ====================================================================
    // 候補日追加 / 削除
    // ====================================================================

    /**
     * 候補日を追加する（幹事のみ、PLANNING のみ）。
     */
    @Transactional
    public MeetupCandidateDateResponse addCandidateDate(UUID villageId,
                                                       UUID meetupId,
                                                       MeetupCandidateDateAddRequest request,
                                                       Long actorUserId) {
        loadActiveVillage(villageId);
        VillageMeetupEntity entity = loadMeetup(villageId, meetupId);
        requireOrganizer(entity, actorUserId);

        if (entity.getStatus() != VillageMeetupStatus.PLANNING) {
            throw new BusinessException(VillageErrorCode.MEETUP_INVALID_STATUS);
        }

        // (date, time) ペアでの重複チェック。MySQL の UNIQUE は TIME NULL を重複許容するため、
        // 終日候補の重複を確実に弾くにはアプリ層で NULL を含めて等価判定する必要がある（#2357）。
        CandidateDateKey key = new CandidateDateKey(request.candidateDate(), request.candidateTime());
        boolean duplicated = candidateDateRepository
                .findByMeetupIdOrderBySortOrderAscCandidateDateAscCandidateTimeAsc(meetupId)
                .stream()
                .anyMatch(d -> key.equals(new CandidateDateKey(d.getCandidateDate(), d.getCandidateTime())));
        if (duplicated) {
            throw new BusinessException(VillageErrorCode.VOTE_DUPLICATE);
        }

        VillageMeetupCandidateDateEntity cd = VillageMeetupCandidateDateEntity.builder()
                .meetupId(meetupId)
                .candidateDate(request.candidateDate())
                .candidateTime(request.candidateTime())
                .sortOrder(request.sortOrder() != null ? request.sortOrder() : 0)
                .build();
        VillageMeetupCandidateDateEntity saved = candidateDateRepository.save(cd);
        return MeetupCandidateDateResponse.of(saved);
    }

    /**
     * 候補日を削除する（幹事のみ、PLANNING のみ）。
     * 投票は CASCADE で連動削除される。
     */
    @Transactional
    public void removeCandidateDate(UUID villageId,
                                    UUID meetupId,
                                    UUID candidateDateId,
                                    Long actorUserId) {
        loadActiveVillage(villageId);
        VillageMeetupEntity entity = loadMeetup(villageId, meetupId);
        requireOrganizer(entity, actorUserId);

        if (entity.getStatus() != VillageMeetupStatus.PLANNING) {
            throw new BusinessException(VillageErrorCode.MEETUP_INVALID_STATUS);
        }

        VillageMeetupCandidateDateEntity cd = candidateDateRepository.findById(candidateDateId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.CANDIDATE_DATE_NOT_FOUND));
        if (!meetupId.equals(cd.getMeetupId())) {
            throw new BusinessException(VillageErrorCode.CANDIDATE_DATE_NOT_FOUND);
        }
        candidateDateRepository.delete(cd);
    }

    // ====================================================================
    // 投票
    // ====================================================================

    /**
     * 候補日に投票する（村人のみ、PLANNING のみ）。
     * 同一候補日への再投票は UPDATE 扱い。
     */
    @Transactional
    public void castVote(UUID villageId,
                         UUID meetupId,
                         UUID candidateDateId,
                         MeetupVoteRequest request,
                         Long actorUserId) {
        loadActiveVillage(villageId);
        requireVillager(villageId, actorUserId);
        VillageMeetupEntity entity = loadMeetup(villageId, meetupId);

        if (entity.getStatus() != VillageMeetupStatus.PLANNING) {
            throw new BusinessException(VillageErrorCode.MEETUP_INVALID_STATUS);
        }

        VillageMeetupCandidateDateEntity cd = candidateDateRepository.findById(candidateDateId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.CANDIDATE_DATE_NOT_FOUND));
        if (!meetupId.equals(cd.getMeetupId())) {
            throw new BusinessException(VillageErrorCode.CANDIDATE_DATE_NOT_FOUND);
        }

        VillageMeetupVoteEntity vote = voteRepository
                .findByCandidateDateIdAndVoterUserId(candidateDateId, actorUserId)
                .orElseGet(() -> VillageMeetupVoteEntity.builder()
                        .candidateDateId(candidateDateId)
                        .voterUserId(actorUserId)
                        .build());
        vote.setVoteType(request.voteType());
        voteRepository.save(vote);

        auditLogService.record(
                AuditEventType.VILLAGE_MEETUP_VOTED.name(),
                actorUserId, null, null, null,
                null, null, null,
                "{\"villageId\":\"" + villageId
                        + "\",\"meetupId\":\"" + meetupId
                        + "\",\"candidateDateId\":\"" + candidateDateId
                        + "\",\"voteType\":\"" + request.voteType() + "\"}"
        );
    }

    /**
     * 寄合の投票集計を取得する（村人のみ）。
     */
    public MeetupVoteSummaryResponse getVoteSummary(UUID villageId, UUID meetupId, Long actorUserId) {
        loadActiveVillage(villageId);
        requireVillager(villageId, actorUserId);
        loadMeetup(villageId, meetupId); // 存在/IDOR チェック

        List<VillageMeetupCandidateDateEntity> dates =
                candidateDateRepository.findByMeetupIdOrderBySortOrderAscCandidateDateAscCandidateTimeAsc(meetupId);
        if (dates.isEmpty()) {
            return MeetupVoteSummaryResponse.builder().meetupId(meetupId).candidates(List.of()).build();
        }
        List<UUID> ids = dates.stream().map(VillageMeetupCandidateDateEntity::getId).toList();
        List<VillageMeetupVoteEntity> votes = voteRepository.findByCandidateDateIdIn(ids);

        Map<UUID, List<VillageMeetupVoteEntity>> grouped = votes.stream()
                .collect(Collectors.groupingBy(VillageMeetupVoteEntity::getCandidateDateId));

        List<MeetupVoteSummaryResponse.CandidateDateSummary> summary = dates.stream()
                .map(cd -> {
                    List<VillageMeetupVoteEntity> list = grouped.getOrDefault(cd.getId(), List.of());
                    int avail = 0, maybe = 0, unavail = 0;
                    for (VillageMeetupVoteEntity v : list) {
                        switch (v.getVoteType()) {
                            case AVAILABLE -> avail++;
                            case MAYBE -> maybe++;
                            case UNAVAILABLE -> unavail++;
                        }
                    }
                    return MeetupVoteSummaryResponse.CandidateDateSummary.builder()
                            .candidateDateId(cd.getId())
                            .candidateDate(cd.getCandidateDate())
                            .candidateTime(cd.getCandidateTime())
                            .availableCount(avail)
                            .maybeCount(maybe)
                            .unavailableCount(unavail)
                            .build();
                })
                .toList();

        return MeetupVoteSummaryResponse.builder()
                .meetupId(meetupId)
                .candidates(summary)
                .build();
    }

    // ====================================================================
    // F17.2 Wave1 ②寄合後半戦 — 出欠（attendance）
    // ====================================================================

    /**
     * 自分の出欠を upsert する（設計書 §4.4.1）。CONFIRMED の寄合のみ受け付ける。
     *
     * <p>実装方式（§4.4.1）: {@code (meetupId, userId)} で既存を検索し、在れば status を更新、
     * 不在なら insert する。UNIQUE 制約の並行 insert 競合（二重タップ等）は
     * {@link DataIntegrityViolationException} を捕捉して1回だけ再検索→更新にフォールバックする。
     * 「今の意思を上書きする」冪等操作なので、新規作成でも既存更新でも常に 200 を返す。</p>
     */
    // isolation=READ_COMMITTED: 悲観ロック取得後の GOING 数カウントを「現在の確定値」で読むため。
    // MySQL 既定の REPEATABLE READ では、本メソッド冒頭の非ロック読み（loadActiveVillage 等）で
    // 確立された一貫スナップショットにカウントが引きずられ、ロック解放直後に相手が確定させた GOING 行を
    // 取りこぼして定員超過を許す。READ_COMMITTED なら各文が最新確定を読むため、直列化が正しく効く（AC-20）。
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MeetupAttendanceResponse upsertAttendance(UUID villageId, UUID meetupId,
                                                     MeetupAttendanceUpsertRequest request, Long actorUserId) {
        loadActiveVillage(villageId);
        VillageMeetupEntity meetup = loadMeetup(villageId, meetupId);
        requireVillager(villageId, actorUserId);
        requireConfirmed(meetup);

        // F17.2 追補: GOING への新規/遷移時のみ定員を強制する（MAYBE/ABSENT は無制約）。
        if (request.status() == VillageMeetupAttendanceStatus.GOING) {
            enforceCapacityForGoing(meetupId, actorUserId);
        }

        VillageMeetupAttendanceEntity saved = writeAttendance(meetupId, actorUserId, request.status());

        auditLogService.record(
                AuditEventType.VILLAGE_MEETUP_ATTENDANCE_SET.name(),
                actorUserId, null, null, null, null, null, null,
                "{\"villageId\":\"" + villageId + "\",\"meetupId\":\"" + meetupId
                        + "\",\"status\":\"" + request.status() + "\"}");

        return MeetupAttendanceResponse.of(saved, resolveUserDisplayName(actorUserId, villageId));
    }

    /**
     * GOING 出欠の定員（capacity）を強制する（F17.2 追補）。
     *
     * <p>親の寄合行を悲観ロック（{@code SELECT ... FOR UPDATE}）で取得してから GOING 数を数え、
     * 判定・書込みまでを同一トランザクションで直列化する。これにより 2 名がほぼ同時に GOING を
     * 叩いても定員を超えない（AC-20）。@Version 楽観ロックだけでは別ユーザーが別の出欠行を insert
     * するケースで親行が dirty にならず衝突しないため、超過を防げない。</p>
     *
     * <ul>
     *   <li>capacity=null は無制限。</li>
     *   <li>既に GOING の本人の再送は満席でも冪等成功（新規カウントに数えない）。</li>
     *   <li>GOING 数が capacity 以上のときの新規 GOING は
     *       {@link VillageErrorCode#MEETUP_CAPACITY_FULL}（VILLAGE_103・409）で拒否する。</li>
     * </ul>
     */
    private void enforceCapacityForGoing(UUID meetupId, Long actorUserId) {
        VillageMeetupEntity locked = meetupRepository.findByIdForUpdate(meetupId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.MEETUP_NOT_FOUND));
        Integer capacity = locked.getCapacity();
        if (capacity == null) {
            return; // 無制限
        }
        // 既に GOING の本人はカウント済み。同状態の再送は満席でも通す（冪等・本人は塞がない）。
        boolean alreadyGoing = attendanceRepository.findByMeetupIdAndUserId(meetupId, actorUserId)
                .map(a -> a.getStatus() == VillageMeetupAttendanceStatus.GOING)
                .orElse(false);
        if (alreadyGoing) {
            return;
        }
        long going = attendanceRepository.countByMeetupIdAndStatus(
                meetupId, VillageMeetupAttendanceStatus.GOING);
        if (going >= capacity) {
            throw new BusinessException(VillageErrorCode.MEETUP_CAPACITY_FULL);
        }
    }

    /** 出欠 upsert の本体（並行 UNIQUE 競合は1回だけ再検索→更新でフォールバック）。 */
    private VillageMeetupAttendanceEntity writeAttendance(UUID meetupId, Long userId,
                                                          com.mannschaft.app.village.entity.enums.VillageMeetupAttendanceStatus status) {
        Optional<VillageMeetupAttendanceEntity> existing =
                attendanceRepository.findByMeetupIdAndUserId(meetupId, userId);
        if (existing.isPresent()) {
            VillageMeetupAttendanceEntity e = existing.get();
            e.setStatus(status);
            return attendanceRepository.save(e);
        }
        try {
            VillageMeetupAttendanceEntity created = VillageMeetupAttendanceEntity.builder()
                    .meetupId(meetupId)
                    .userId(userId)
                    .status(status)
                    .build();
            return attendanceRepository.saveAndFlush(created);
        } catch (DataIntegrityViolationException dup) {
            // 並行 insert 競合（uk_vma_meetup_user）。既存を読み直して更新へフォールバック。
            VillageMeetupAttendanceEntity e = attendanceRepository.findByMeetupIdAndUserId(meetupId, userId)
                    .orElseThrow(() -> dup);
            e.setStatus(status);
            return attendanceRepository.save(e);
        }
    }

    /** 寄合の出欠一覧（付いた順・村ニックネーム表示・設計書 §13.5）。 */
    public List<MeetupAttendanceResponse> listAttendances(UUID villageId, UUID meetupId,
                                                          Long actorUserId, Pageable pageable) {
        loadActiveVillage(villageId);
        loadMeetup(villageId, meetupId);
        requireVillager(villageId, actorUserId);
        Pageable resolved = resolvePageableAsc(pageable);
        List<VillageMeetupAttendanceEntity> rows =
                attendanceRepository.findByMeetupIdOrderByCreatedAtAsc(meetupId, resolved).getContent();
        Map<Long, String> names = resolveDisplayNames(
                rows.stream().map(VillageMeetupAttendanceEntity::getUserId).toList(), villageId);
        return rows.stream()
                .map(a -> MeetupAttendanceResponse.of(a, names.get(a.getUserId())))
                .toList();
    }

    // ====================================================================
    // F17.2 Wave1 ②寄合後半戦 — コメント（comment）
    // ====================================================================

    /** コメントを投稿する（村人・設計書 §4.4）。 */
    @Transactional
    public MeetupCommentResponse createComment(UUID villageId, UUID meetupId,
                                               MeetupCommentCreateRequest request, Long actorUserId) {
        loadActiveVillage(villageId);
        VillageMeetupEntity meetup = loadMeetup(villageId, meetupId);
        requireVillager(villageId, actorUserId);
        rejectIfCancelled(meetup);

        VillageMeetupCommentEntity entity = VillageMeetupCommentEntity.builder()
                .meetupId(meetupId)
                .authorUserId(actorUserId)
                .body(request.body())
                .build();
        VillageMeetupCommentEntity saved = commentRepository.save(entity);
        return MeetupCommentResponse.of(saved, resolveUserDisplayName(actorUserId, villageId));
    }

    /** コメント一覧（作成日昇順＝古い順・村ニックネーム表示・設計書 §13.5）。 */
    public List<MeetupCommentResponse> listComments(UUID villageId, UUID meetupId,
                                                    Long actorUserId, Pageable pageable) {
        loadActiveVillage(villageId);
        loadMeetup(villageId, meetupId);
        requireVillager(villageId, actorUserId);
        Pageable resolved = resolvePageableAsc(pageable);
        List<VillageMeetupCommentEntity> rows =
                commentRepository.findByMeetupIdAndDeletedAtIsNullOrderByCreatedAtAsc(meetupId, resolved).getContent();
        Map<Long, String> names = resolveDisplayNames(
                rows.stream().map(VillageMeetupCommentEntity::getAuthorUserId).toList(), villageId);
        return rows.stream()
                .map(c -> MeetupCommentResponse.of(c, names.get(c.getAuthorUserId())))
                .toList();
    }

    /** コメントを論理削除する（投稿者本人＋村長/長老のみ・設計書 §4.4/AC-09）。 */
    @Transactional
    public void deleteComment(UUID villageId, UUID meetupId, UUID commentId, Long actorUserId) {
        loadActiveVillage(villageId);
        loadMeetup(villageId, meetupId);
        requireVillager(villageId, actorUserId);

        VillageMeetupCommentEntity comment = loadComment(meetupId, commentId);
        boolean isAuthor = comment.getAuthorUserId().equals(actorUserId);
        if (!isAuthor && !isModerator(villageId, actorUserId)) {
            throw new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN);
        }
        comment.setDeletedAt(LocalDateTime.now());
        commentRepository.save(comment);
    }

    // ====================================================================
    // F17.2 Wave1 ②寄合後半戦 — 宿題 TODO
    // ====================================================================

    /** 宿題を作成する（幹事＋村長/長老・設計書 §4.4）。{@code assigneeUserId} 未指定は手挙げ待ち。 */
    @Transactional
    public MeetupTodoResponse createTodo(UUID villageId, UUID meetupId,
                                         MeetupTodoCreateRequest request, Long actorUserId) {
        loadActiveVillage(villageId);
        VillageMeetupEntity meetup = loadMeetup(villageId, meetupId);
        requireOrganizerOrModerator(villageId, meetup, actorUserId);
        rejectIfCancelled(meetup);

        VillageMeetupTodoEntity entity = VillageMeetupTodoEntity.builder()
                .meetupId(meetupId)
                .title(request.title())
                .assigneeUserId(request.assigneeUserId())
                .createdBy(actorUserId)
                .build();
        VillageMeetupTodoEntity saved = todoRepository.save(entity);
        return MeetupTodoResponse.of(saved, resolveUserDisplayName(saved.getAssigneeUserId(), villageId));
    }

    /** 宿題一覧（作成日昇順・設計書 §13.5）。 */
    public List<MeetupTodoResponse> listTodos(UUID villageId, UUID meetupId,
                                              Long actorUserId, Pageable pageable) {
        loadActiveVillage(villageId);
        loadMeetup(villageId, meetupId);
        requireVillager(villageId, actorUserId);
        Pageable resolved = resolvePageableAsc(pageable);
        List<VillageMeetupTodoEntity> rows =
                todoRepository.findByMeetupIdAndDeletedAtIsNullOrderByCreatedAtAsc(meetupId, resolved).getContent();
        // assigneeUserId が null の TODO（手挙げ待ち）は names に含まれず get(null)=null → 表示名 null を維持。
        Map<Long, String> names = resolveDisplayNames(
                rows.stream().map(VillageMeetupTodoEntity::getAssigneeUserId).toList(), villageId);
        return rows.stream()
                .map(t -> MeetupTodoResponse.of(t, names.get(t.getAssigneeUserId())))
                .toList();
    }

    /**
     * 未割当 TODO を自分に割り当てる（手挙げ・村人本人・設計書 §4.3/AC-10）。
     * 既に割当済みなら {@link VillageErrorCode#MEETUP_TODO_ALREADY_CLAIMED}（409）。
     */
    @Transactional
    public MeetupTodoResponse claimTodo(UUID villageId, UUID meetupId, UUID todoId, Long actorUserId) {
        loadActiveVillage(villageId);
        VillageMeetupEntity meetup = loadMeetup(villageId, meetupId);
        requireVillager(villageId, actorUserId);
        rejectIfCancelled(meetup);

        VillageMeetupTodoEntity todo = loadTodo(meetupId, todoId);
        if (todo.getAssigneeUserId() != null) {
            throw new BusinessException(VillageErrorCode.MEETUP_TODO_ALREADY_CLAIMED);
        }
        todo.setAssigneeUserId(actorUserId);
        VillageMeetupTodoEntity saved = todoRepository.save(todo);

        auditLogService.record(
                AuditEventType.VILLAGE_MEETUP_TODO_CLAIMED.name(),
                actorUserId, null, null, null, null, null, null,
                "{\"villageId\":\"" + villageId + "\",\"meetupId\":\"" + meetupId
                        + "\",\"todoId\":\"" + todoId + "\"}");

        return MeetupTodoResponse.of(saved, resolveUserDisplayName(actorUserId, villageId));
    }

    /**
     * 宿題を完了にする（手挙げ者本人＋幹事のみ・設計書 §4.3/AC-11）。
     * それ以外は {@link VillageErrorCode#MEETUP_TODO_NOT_ASSIGNEE}（403）。
     */
    @Transactional
    public MeetupTodoResponse completeTodo(UUID villageId, UUID meetupId, UUID todoId, Long actorUserId) {
        loadActiveVillage(villageId);
        VillageMeetupEntity meetup = loadMeetup(villageId, meetupId);
        requireVillager(villageId, actorUserId);
        rejectIfCancelled(meetup);

        VillageMeetupTodoEntity todo = loadTodo(meetupId, todoId);
        boolean isAssignee = actorUserId.equals(todo.getAssigneeUserId());
        boolean isOrganizer = meetup.getOrganizerUserId().equals(actorUserId);
        if (!isAssignee && !isOrganizer) {
            throw new BusinessException(VillageErrorCode.MEETUP_TODO_NOT_ASSIGNEE);
        }
        todo.setDoneAt(LocalDateTime.now());
        VillageMeetupTodoEntity saved = todoRepository.save(todo);

        auditLogService.record(
                AuditEventType.VILLAGE_MEETUP_TODO_COMPLETED.name(),
                actorUserId, null, null, null, null, null, null,
                "{\"villageId\":\"" + villageId + "\",\"meetupId\":\"" + meetupId
                        + "\",\"todoId\":\"" + todoId + "\"}");

        return MeetupTodoResponse.of(saved, resolveUserDisplayName(saved.getAssigneeUserId(), villageId));
    }

    /**
     * 宿題を手放す（割当を未割当へ戻す・<strong>本人のみ</strong>・設計書 §4.3/AC-12）。
     * 幹事でも他人の割当は手放せない（権限の非対称）。本人以外は
     * {@link VillageErrorCode#MEETUP_TODO_NOT_ASSIGNEE}（403）。
     */
    @Transactional
    public MeetupTodoResponse releaseTodo(UUID villageId, UUID meetupId, UUID todoId, Long actorUserId) {
        loadActiveVillage(villageId);
        VillageMeetupEntity meetup = loadMeetup(villageId, meetupId);
        requireVillager(villageId, actorUserId);
        rejectIfCancelled(meetup);

        VillageMeetupTodoEntity todo = loadTodo(meetupId, todoId);
        if (!actorUserId.equals(todo.getAssigneeUserId())) {
            // 幹事であっても他人の割当は手放せない（手放しは本人の自発的行為に限る）。
            throw new BusinessException(VillageErrorCode.MEETUP_TODO_NOT_ASSIGNEE);
        }
        todo.setAssigneeUserId(null);
        VillageMeetupTodoEntity saved = todoRepository.save(todo);

        auditLogService.record(
                AuditEventType.VILLAGE_MEETUP_TODO_RELEASED.name(),
                actorUserId, null, null, null, null, null, null,
                "{\"villageId\":\"" + villageId + "\",\"meetupId\":\"" + meetupId
                        + "\",\"todoId\":\"" + todoId + "\"}");

        return MeetupTodoResponse.of(saved, null);
    }

    // ====================================================================
    // 共通ヘルパ
    // ====================================================================

    private VillageEntity loadActiveVillage(UUID villageId) {
        VillageEntity v = villageRepository.findById(villageId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND));
        if (v.getDeletedAt() != null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
        }
        if (v.getArchivedAt() != null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_ALREADY_ARCHIVED);
        }
        return v;
    }

    private VillageMeetupEntity loadMeetup(UUID villageId, UUID meetupId) {
        VillageMeetupEntity entity = meetupRepository.findById(meetupId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.MEETUP_NOT_FOUND));
        if (!villageId.equals(entity.getVillageId()) || entity.getDeletedAt() != null) {
            throw new BusinessException(VillageErrorCode.MEETUP_NOT_FOUND);
        }
        return entity;
    }

    /**
     * 実行者が当該村の<strong>現役</strong>村人であることを検証する（ロールは問わない）。
     *
     * <p>「現役」の判定（退村済み {@code leftAt} / BAN 済み {@code bannedAt} の除外）は
     * {@code findActiveByVillageIdAndSubject} のクエリに委譲する（#2284 §12）。
     * 以前は「BAN 状態の扱いは別フローで吸収する」として BAN を素通ししていたが、
     * その別フローは存在せず、BAN された村人が寄合の作成・投票を続行できた。</p>
     */
    private void requireVillager(UUID villageId, Long actorUserId) {
        membershipRepository
                .findActiveByVillageIdAndSubject(villageId, VillageSubjectType.USER, actorUserId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.MEETUP_NOT_MEMBER));
    }

    private void requireOrganizer(VillageMeetupEntity entity, Long actorUserId) {
        if (!entity.getOrganizerUserId().equals(actorUserId)) {
            throw new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN);
        }
    }

    /**
     * 幹事（organizer）または現役モデレーター（HEADMAN/ELDER）であることを要求する（F17.2・§4.4/AC-13）。
     * いずれでもなければ {@link VillageErrorCode#MODERATION_FORBIDDEN}（403）。
     */
    private void requireOrganizerOrModerator(UUID villageId, VillageMeetupEntity entity, Long actorUserId) {
        if (entity.getOrganizerUserId().equals(actorUserId)) {
            return;
        }
        if (!isModerator(villageId, actorUserId)) {
            throw new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN);
        }
    }

    /** 当該ユーザーが対象村の現役モデレーター（HEADMAN/ELDER）かを判定する（BAN/退村はクエリで除外）。 */
    private boolean isModerator(UUID villageId, Long actorUserId) {
        return membershipRepository
                .findActiveByVillageIdAndSubject(villageId, VillageSubjectType.USER, actorUserId)
                .map(m -> m.getRole() == VillageRole.HEADMAN || m.getRole() == VillageRole.ELDER)
                .orElse(false);
    }

    /** 出欠受付は CONFIRMED 限定（PLANNING は候補日投票と住み分け・設計書 §4.5/AC-08）。 */
    private void requireConfirmed(VillageMeetupEntity meetup) {
        if (meetup.getStatus() != VillageMeetupStatus.CONFIRMED) {
            throw new BusinessException(VillageErrorCode.MEETUP_NOT_CONFIRMED);
        }
    }

    /**
     * CANCELLED（中止済み）の寄合への新規書き込みを拒否する（設計書 §4.5「CANCELLED=読み取りのみ」）。
     * コメント/宿題/決まったこと等の書込み系で使用する（既存 {@link VillageErrorCode#MEETUP_INVALID_STATUS} 流用）。
     */
    private void rejectIfCancelled(VillageMeetupEntity meetup) {
        if (meetup.getStatus() == VillageMeetupStatus.CANCELLED) {
            throw new BusinessException(VillageErrorCode.MEETUP_INVALID_STATUS);
        }
    }

    /** コメントを寄合スコープで取得する。他寄合・論理削除済みは 404（IDOR 秘匿・MEETUP_NOT_FOUND）。 */
    private VillageMeetupCommentEntity loadComment(UUID meetupId, UUID commentId) {
        VillageMeetupCommentEntity c = commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.MEETUP_NOT_FOUND));
        if (!meetupId.equals(c.getMeetupId()) || c.getDeletedAt() != null) {
            throw new BusinessException(VillageErrorCode.MEETUP_NOT_FOUND);
        }
        return c;
    }

    /** 宿題を寄合スコープで取得する。他寄合・論理削除済みは 404（IDOR 秘匿・MEETUP_NOT_FOUND）。 */
    private VillageMeetupTodoEntity loadTodo(UUID meetupId, UUID todoId) {
        VillageMeetupTodoEntity t = todoRepository.findById(todoId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.MEETUP_NOT_FOUND));
        if (!meetupId.equals(t.getMeetupId()) || t.getDeletedAt() != null) {
            throw new BusinessException(VillageErrorCode.MEETUP_NOT_FOUND);
        }
        return t;
    }

    /**
     * 村人の表示名を村ニックネームで解決する（実名スナップショット禁止・§10 G4）。
     * 村内ニックネーム → 全村共通ニックネーム → {@code "USER:#id"} の順にフォールバック。
     */
    private String resolveUserDisplayName(Long userId, UUID villageId) {
        if (userId == null) {
            return null;
        }
        if (villageId != null) {
            Optional<UserVillageNicknameEntity> villageNick =
                    nicknameRepository.findByUserIdAndVillageId(userId, villageId);
            if (villageNick.isPresent()) {
                return villageNick.get().getNickname();
            }
        }
        return nicknameRepository.findByUserIdAndVillageIdIsNull(userId)
                .map(UserVillageNicknameEntity::getNickname)
                .orElse("USER:#" + userId);
    }

    /**
     * 村人集合の表示名を村ニックネームで<strong>一括</strong>解決する（一覧の N+1 回避・検分 §3）。
     *
     * <p>単票版 {@link #resolveUserDisplayName(Long, UUID)} と同一の解決順（村内ニックネーム →
     * 全村共通ニックネーム → {@code "USER:#id"}）を保つが、クエリを user_id 集合の先読み 2 回に抑える。
     * {@code null} の userId は結果マップに含めない（呼び出し側で {@code null} 表示を維持する）。</p>
     */
    private Map<Long, String> resolveDisplayNames(java.util.Collection<Long> userIds, UUID villageId) {
        Set<Long> ids = userIds.stream()
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> result = new java.util.HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        if (villageId != null) {
            for (UserVillageNicknameEntity n : nicknameRepository.findByUserIdInAndVillageId(ids, villageId)) {
                result.put(n.getUserId(), n.getNickname());
            }
        }
        Set<Long> remaining = ids.stream()
                .filter(id -> !result.containsKey(id))
                .collect(Collectors.toSet());
        if (!remaining.isEmpty()) {
            for (UserVillageNicknameEntity n : nicknameRepository.findByUserIdInAndVillageIdIsNull(remaining)) {
                result.putIfAbsent(n.getUserId(), n.getNickname());
            }
        }
        for (Long id : ids) {
            result.putIfAbsent(id, "USER:#" + id);
        }
        return result;
    }

    /** 昇順（作成日）一覧用に Pageable を解決する（既定 20・上限 {@link #MAX_PAGE_SIZE}）。 */
    private Pageable resolvePageableAsc(Pageable pageable) {
        if (pageable == null) {
            return PageRequest.of(0, DEFAULT_LIST_PAGE_SIZE);
        }
        int size = Math.min(pageable.getPageSize(), MAX_PAGE_SIZE);
        return PageRequest.of(Math.max(pageable.getPageNumber(), 0), size <= 0 ? DEFAULT_LIST_PAGE_SIZE : size);
    }

    private MeetupResponse buildResponseWithCandidates(VillageMeetupEntity entity) {
        List<MeetupCandidateDateResponse> candidates = candidateDateRepository
                .findByMeetupIdOrderBySortOrderAscCandidateDateAscCandidateTimeAsc(entity.getId())
                .stream().map(MeetupCandidateDateResponse::of).toList();
        // F17.2 追補: GOING 実数を載せる（remainingSlots は MeetupResponse.of が capacity から算出）。
        long goingCount = attendanceRepository.countByMeetupIdAndStatus(
                entity.getId(), VillageMeetupAttendanceStatus.GOING);
        return MeetupResponse.of(entity, candidates, goingCount);
    }

    /**
     * 候補日の同一性キー（日付 + 任意の時刻）。#2357
     *
     * <p>{@code time} が {@code null}（終日）でも等価判定に含めるための小さな値オブジェクト。
     * MySQL の UNIQUE は TIME NULL を重複許容するため、重複検査はこのキーでアプリ層でも行う。</p>
     */
    private record CandidateDateKey(LocalDate date, LocalTime time) {
    }

    private Pageable resolvePageable(Pageable pageable) {
        if (pageable == null) {
            return PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        }
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
        }
        return pageable;
    }
}
