package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.MeetupCandidateDateAddRequest;
import com.mannschaft.app.village.dto.MeetupCandidateDateResponse;
import com.mannschaft.app.village.dto.MeetupConfirmRequest;
import com.mannschaft.app.village.dto.MeetupCreateRequest;
import com.mannschaft.app.village.dto.MeetupResponse;
import com.mannschaft.app.village.dto.MeetupUpdateRequest;
import com.mannschaft.app.village.dto.MeetupVoteRequest;
import com.mannschaft.app.village.dto.MeetupVoteSummaryResponse;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMeetupCandidateDateEntity;
import com.mannschaft.app.village.entity.VillageMeetupEntity;
import com.mannschaft.app.village.entity.VillageMeetupVoteEntity;
import com.mannschaft.app.village.entity.enums.VillageMeetupStatus;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageMeetupCandidateDateRepository;
import com.mannschaft.app.village.repository.VillageMeetupRepository;
import com.mannschaft.app.village.repository.VillageMeetupVoteRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    /** 候補日の最大件数（DTO の @Size と同期）。 */
    private static final int MAX_CANDIDATE_DATES = 30;

    private final VillageMeetupRepository meetupRepository;
    private final VillageMeetupCandidateDateRepository candidateDateRepository;
    private final VillageMeetupVoteRepository voteRepository;
    private final VillageRepository villageRepository;
    private final VillageMembershipRepository membershipRepository;
    private final AuditLogService auditLogService;

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
        // 候補日重複（同一リスト内）チェック
        Set<java.time.LocalDate> uniq = new HashSet<>(request.candidateDates());
        if (uniq.size() != request.candidateDates().size()) {
            throw new BusinessException(VillageErrorCode.VOTE_DUPLICATE);
        }

        VillageMeetupEntity entity = VillageMeetupEntity.builder()
                .villageId(villageId)
                .title(request.title())
                .description(request.description())
                .organizerUserId(actorUserId)
                .status(VillageMeetupStatus.PLANNING)
                .location(request.location())
                .build();
        VillageMeetupEntity saved = meetupRepository.save(entity);

        // 候補日登録
        int order = 0;
        for (java.time.LocalDate date : request.candidateDates()) {
            VillageMeetupCandidateDateEntity cd = VillageMeetupCandidateDateEntity.builder()
                    .meetupId(saved.getId())
                    .candidateDate(date)
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
        requireOrganizer(entity, actorUserId);

        if (entity.getStatus() != VillageMeetupStatus.PLANNING) {
            throw new BusinessException(VillageErrorCode.MEETUP_INVALID_STATUS);
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
        return page.getContent().stream()
                // 一覧では候補日は省略（パフォーマンス）
                .map(m -> MeetupResponse.of(m, null))
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

        candidateDateRepository.findByMeetupIdAndCandidateDate(meetupId, request.candidateDate())
                .ifPresent(d -> { throw new BusinessException(VillageErrorCode.VOTE_DUPLICATE); });

        VillageMeetupCandidateDateEntity cd = VillageMeetupCandidateDateEntity.builder()
                .meetupId(meetupId)
                .candidateDate(request.candidateDate())
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
                candidateDateRepository.findByMeetupIdOrderBySortOrderAscCandidateDateAsc(meetupId);
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

    private MeetupResponse buildResponseWithCandidates(VillageMeetupEntity entity) {
        List<MeetupCandidateDateResponse> candidates = candidateDateRepository
                .findByMeetupIdOrderBySortOrderAscCandidateDateAsc(entity.getId())
                .stream().map(MeetupCandidateDateResponse::of).toList();
        return MeetupResponse.of(entity, candidates);
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
