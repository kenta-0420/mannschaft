package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timeline.service.TimelinePostService;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.FestivalLivePostResponse;
import com.mannschaft.app.village.dto.FestivalLivePostTagRequest;
import com.mannschaft.app.village.dto.FestivalRsvpResponse;
import com.mannschaft.app.village.dto.FestivalRsvpUpsertRequest;
import com.mannschaft.app.village.entity.UserVillageNicknameEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageFestivalEntity;
import com.mannschaft.app.village.entity.VillageFestivalLivePostEntity;
import com.mannschaft.app.village.entity.VillageFestivalRsvpEntity;
import com.mannschaft.app.village.entity.enums.VillageFestivalStatus;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.UserVillageNicknameRepository;
import com.mannschaft.app.village.repository.VillageFestivalLivePostRepository;
import com.mannschaft.app.village.repository.VillageFestivalRepository;
import com.mannschaft.app.village.repository.VillageFestivalRsvpRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * F17.2 Wave2 ③お祭りの参加レイヤー — 参加表明（RSVP）＋実況タグのサービス（設計書 §5）。
 *
 * <p>認可は Service 層ガード（{@link #requireVillager}）で行う（呼び出し元まかせにしない）。
 * 表示名は<strong>村ニックネーム</strong>で解決する（実名スナップショット禁止・§10 G4）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VillageFestivalParticipationService {

    private final VillageFestivalRepository festivalRepository;
    private final VillageFestivalRsvpRepository rsvpRepository;
    private final VillageFestivalLivePostRepository livePostRepository;
    private final VillageMembershipRepository membershipRepository;
    private final UserVillageNicknameRepository nicknameRepository;
    private final TimelinePostService timelinePostService;
    private final AuditLogService auditLogService;
    private final VillageAccessGate accessGate;

    // ====================================================================
    // 参加表明（RSVP）
    // ====================================================================

    /** 自分の参加表明を upsert する（SCHEDULED/ACTIVE のみ・冪等 200・設計書 §5.6/§4.4.1）。 */
    @Transactional
    public FestivalRsvpResponse upsertRsvp(UUID villageId, UUID festivalId,
                                           FestivalRsvpUpsertRequest request, Long actorUserId) {
        loadActiveVillage(villageId, actorUserId);
        VillageFestivalEntity festival = loadFestival(villageId, festivalId);
        requireVillager(villageId, actorUserId);
        requireRsvpOpen(festival);

        VillageFestivalRsvpEntity saved = writeRsvp(festivalId, actorUserId, request);

        auditLogService.record(
                AuditEventType.VILLAGE_FESTIVAL_RSVP_SET.name(),
                actorUserId, null, null, null, null, null, null,
                "{\"villageId\":\"" + villageId + "\",\"festivalId\":\"" + festivalId
                        + "\",\"status\":\"" + request.status() + "\"}");

        return FestivalRsvpResponse.of(saved, resolveUserDisplayName(actorUserId, villageId));
    }

    /** RSVP upsert の本体（並行 UNIQUE 競合は1回だけ再検索→更新でフォールバック・§4.4.1）。 */
    private VillageFestivalRsvpEntity writeRsvp(UUID festivalId, Long userId, FestivalRsvpUpsertRequest request) {
        Optional<VillageFestivalRsvpEntity> existing =
                rsvpRepository.findByFestivalIdAndUserId(festivalId, userId);
        if (existing.isPresent()) {
            VillageFestivalRsvpEntity e = existing.get();
            e.setStatus(request.status());
            e.setRoleLabel(request.roleLabel());
            return rsvpRepository.save(e);
        }
        try {
            VillageFestivalRsvpEntity created = VillageFestivalRsvpEntity.builder()
                    .festivalId(festivalId)
                    .userId(userId)
                    .status(request.status())
                    .roleLabel(request.roleLabel())
                    .build();
            return rsvpRepository.saveAndFlush(created);
        } catch (DataIntegrityViolationException dup) {
            VillageFestivalRsvpEntity e = rsvpRepository.findByFestivalIdAndUserId(festivalId, userId)
                    .orElseThrow(() -> dup);
            e.setStatus(request.status());
            e.setRoleLabel(request.roleLabel());
            return rsvpRepository.save(e);
        }
    }

    /** 参加表明を取り消す（レコード削除＝無回答へ戻す・SCHEDULED/ACTIVE のみ・設計書 §5.6/§12.2）。 */
    @Transactional
    public void deleteRsvp(UUID villageId, UUID festivalId, Long actorUserId) {
        loadActiveVillage(villageId, actorUserId);
        VillageFestivalEntity festival = loadFestival(villageId, festivalId);
        requireVillager(villageId, actorUserId);
        requireRsvpOpen(festival);

        rsvpRepository.deleteByFestivalIdAndUserId(festivalId, actorUserId);
        auditLogService.record(
                AuditEventType.VILLAGE_FESTIVAL_RSVP_CANCELLED.name(),
                actorUserId, null, null, null, null, null, null,
                "{\"villageId\":\"" + villageId + "\",\"festivalId\":\"" + festivalId + "\"}");
    }

    /** 参加者一覧（作成順・村ニックネーム表示・設計書 §13.5）。 */
    public List<FestivalRsvpResponse> listRsvps(UUID villageId, UUID festivalId,
                                                Long actorUserId, Pageable pageable) {
        loadActiveVillage(villageId, actorUserId);
        loadFestival(villageId, festivalId);
        requireVillager(villageId, actorUserId);

        List<VillageFestivalRsvpEntity> rows =
                rsvpRepository.findByFestivalIdOrderByCreatedAtAsc(festivalId, pageable).getContent();
        Map<Long, String> names = resolveDisplayNames(
                rows.stream().map(VillageFestivalRsvpEntity::getUserId).toList(), villageId);
        return rows.stream()
                .map(r -> FestivalRsvpResponse.of(r, names.get(r.getUserId())))
                .toList();
    }

    // ====================================================================
    // 実況（live-posts）
    // ====================================================================

    /** 実況タグを付ける（ACTIVE 中のみ・二重タグは 409・設計書 §5.4/§5.6/AC-16）。 */
    @Transactional
    public FestivalLivePostResponse tagLivePost(UUID villageId, UUID festivalId,
                                                FestivalLivePostTagRequest request, Long actorUserId) {
        loadActiveVillage(villageId, actorUserId);
        VillageFestivalEntity festival = loadFestival(villageId, festivalId);
        requireVillager(villageId, actorUserId);

        // 実況は開催中（ACTIVE）のみ受け付ける。
        if (festival.getStatus() != VillageFestivalStatus.ACTIVE) {
            throw new BusinessException(VillageErrorCode.FESTIVAL_LIVE_NOT_ACTIVE);
        }
        Long timelinePostId = request.timelinePostId();
        // 紐付け対象は「生存している当該村の VILLAGE 投稿」であること（越境取り違え・削除済み参照を防ぐ）。
        if (!timelinePostService.isAliveVillagePost(timelinePostId, villageId)) {
            throw new BusinessException(VillageErrorCode.FESTIVAL_NOT_FOUND);
        }
        // 二重タグは 409（冪等に握り潰さない・対処療法禁止・AC-16）。
        if (livePostRepository.existsByFestivalIdAndTimelinePostId(festivalId, timelinePostId)) {
            throw new BusinessException(VillageErrorCode.FESTIVAL_LIVE_POST_DUPLICATE);
        }

        VillageFestivalLivePostEntity saved;
        try {
            saved = livePostRepository.saveAndFlush(VillageFestivalLivePostEntity.builder()
                    .festivalId(festivalId)
                    .timelinePostId(timelinePostId)
                    .build());
        } catch (DataIntegrityViolationException dup) {
            // 並行二重タグ（複合PK競合）。明示エラーに倒す。
            throw new BusinessException(VillageErrorCode.FESTIVAL_LIVE_POST_DUPLICATE);
        }

        auditLogService.record(
                AuditEventType.VILLAGE_FESTIVAL_LIVE_POST_TAGGED.name(),
                actorUserId, null, null, null, null, null, null,
                "{\"villageId\":\"" + villageId + "\",\"festivalId\":\"" + festivalId
                        + "\",\"timelinePostId\":" + timelinePostId + "}");

        return FestivalLivePostResponse.of(saved);
    }

    /** 実況一覧（新しい順・timeline 側 deleted_at 済みは除外・設計書 §5.5/§5.6/AC-17c）。 */
    public List<FestivalLivePostResponse> listLivePosts(UUID villageId, UUID festivalId,
                                                        Long actorUserId, Pageable pageable) {
        loadActiveVillage(villageId, actorUserId);
        loadFestival(villageId, festivalId);
        requireVillager(villageId, actorUserId);

        List<VillageFestivalLivePostEntity> rows =
                livePostRepository.findByFestivalIdOrderByCreatedAtDesc(festivalId, pageable).getContent();
        Set<Long> alive = timelinePostService.filterAliveVillagePostIds(
                rows.stream().map(VillageFestivalLivePostEntity::getTimelinePostId).toList(), villageId);
        return rows.stream()
                .filter(lp -> alive.contains(lp.getTimelinePostId()))
                .map(FestivalLivePostResponse::of)
                .toList();
    }

    // ====================================================================
    // ガード / ロード / 解決ヘルパー
    // ====================================================================

    /**
     * 稼働中かつ操作者に可視な村を取得する（判定は {@link VillageAccessGate} に一元化）。
     *
     * <p>非公開(UNLISTED)村を非村人が叩いた場合は、実在しない村 ID と<b>同一の</b>
     * {@code VILLAGE_NOT_FOUND} を返して村の存在ごと秘匿する。公開(PUBLIC)村は素通りし、
     * 非村人かどうかの 403 判定は従来どおり本サービスの呼び出し元に残る。
     * 判定順序とその理由は {@link VillageAccessGate#loadActiveVillage} の Javadoc を参照。</p>
     */
    private VillageEntity loadActiveVillage(UUID villageId, Long actorUserId) {
        return accessGate.loadActiveVillage(villageId, actorUserId);
    }

    /** 祭を村スコープで取得。他村・論理削除済みは 404（IDOR 秘匿・FESTIVAL_NOT_FOUND）。 */
    private VillageFestivalEntity loadFestival(UUID villageId, UUID festivalId) {
        VillageFestivalEntity f = festivalRepository.findById(festivalId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.FESTIVAL_NOT_FOUND));
        if (!villageId.equals(f.getVillageId()) || f.getDeletedAt() != null) {
            throw new BusinessException(VillageErrorCode.FESTIVAL_NOT_FOUND);
        }
        return f;
    }

    /** 実行者が当該村の現役村人であることを検証する（非メンバーは 403・VILLAGE_074 系前例）。 */
    private void requireVillager(UUID villageId, Long actorUserId) {
        membershipRepository
                .findActiveByVillageIdAndSubject(villageId, VillageSubjectType.USER, actorUserId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.MEETUP_NOT_MEMBER));
    }

    /** RSVP は SCHEDULED / ACTIVE のみ受け付ける（ENDED/CANCELLED は VILLAGE_097・§5.6/§12.2）。 */
    private void requireRsvpOpen(VillageFestivalEntity festival) {
        VillageFestivalStatus s = festival.getStatus();
        if (s != VillageFestivalStatus.SCHEDULED && s != VillageFestivalStatus.ACTIVE) {
            throw new BusinessException(VillageErrorCode.FESTIVAL_RSVP_NOT_OPEN);
        }
    }

    /**
     * 村人の表示名を村ニックネームで解決する（村内 → 全村共通 → {@code "USER:#id"}）。
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

    /** 村人集合の表示名を村ニックネームで一括解決する（一覧の N+1 回避）。 */
    private Map<Long, String> resolveDisplayNames(java.util.Collection<Long> userIds, UUID villageId) {
        Set<Long> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        if (villageId != null) {
            for (UserVillageNicknameEntity n : nicknameRepository.findByUserIdInAndVillageId(ids, villageId)) {
                result.put(n.getUserId(), n.getNickname());
            }
        }
        Set<Long> remaining = ids.stream().filter(id -> !result.containsKey(id)).collect(Collectors.toSet());
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
}
