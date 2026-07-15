package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.FestivalCreateRequest;
import com.mannschaft.app.village.dto.FestivalResponse;
import com.mannschaft.app.village.dto.FestivalUpdateRequest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageFestivalEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageFestivalStatus;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageFestivalRepository;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * F17.1 Phase 2 U5 — 村お祭りサービス（設計書 §2.2 / §13.2）。
 *
 * <p>期間付き notice として動作するお祭りエンティティの CRUD を提供する。
 * 状態遷移（SCHEDULED → ACTIVE → ENDED）は基本的に
 * {@link com.mannschaft.app.village.batch.VillageFestivalStateTransitionBatchService}
 * が 15 分ごとに自動更新するが、作成時のみ「すでに開始時刻を過ぎている」場合に
 * 初期 status を {@code ACTIVE} とする。</p>
 *
 * <h2>権限</h2>
 * <ul>
 *   <li>作成・更新・中止: HEADMAN または ELDER のみ
 *       （{@link VillageErrorCode#MODERATION_FORBIDDEN}）</li>
 *   <li>一覧・詳細取得: 認可は Controller 層で行う（村人 / VISITOR の閲覧範囲は U6 で定義）</li>
 * </ul>
 *
 * <h2>原則準拠</h2>
 * <ul>
 *   <li>原則1: 作成者は user_id だけ保持し FK は張らない。</li>
 *   <li>原則5: {@code @Transactional} は village ドメイン内に閉じる。</li>
 *   <li>タイムゾーン: Phase 2 では UTC 固定。村ローカル TZ 対応は Phase 3 へ繰越。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VillageFestivalService {

    /** {@code #RRGGBB} 形式の HEX カラーコード。 */
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("^#[0-9a-fA-F]{6}$");

    /** 一覧の最大ページサイズ。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final VillageFestivalRepository festivalRepository;
    private final VillageRepository villageRepository;
    private final VillageMembershipRepository membershipRepository;
    private final AuditLogService auditLogService;

    // ====================================================================
    // 作成
    // ====================================================================

    /**
     * 村のお祭りを作成する（HEADMAN / ELDER のみ）。
     *
     * <p>初期 status は現在時刻と {@code startsAt} の関係で決定する:</p>
     * <ul>
     *   <li>{@code now < startsAt} → {@code SCHEDULED}</li>
     *   <li>{@code startsAt <= now < endsAt} → {@code ACTIVE}</li>
     *   <li>{@code now >= endsAt} は VILLAGE_060 (期間不正) として拒否する
     *       （過去の祭りを作成する意味はないため）</li>
     * </ul>
     */
    @Transactional
    public FestivalResponse createFestival(UUID villageId, FestivalCreateRequest request, Long actorUserId) {
        loadActiveVillage(villageId);
        requireHeadmanOrElder(villageId, actorUserId);

        validatePeriod(request.startsAt(), request.endsAt());
        validateColor(request.themeColorHex());

        LocalDateTime now = LocalDateTime.now();
        if (!request.endsAt().isAfter(now)) {
            // 既に終了時刻を過ぎている祭りは作成不可
            throw new BusinessException(VillageErrorCode.FESTIVAL_INVALID_PERIOD);
        }
        VillageFestivalStatus initialStatus =
                request.startsAt().isAfter(now) ? VillageFestivalStatus.SCHEDULED : VillageFestivalStatus.ACTIVE;

        VillageFestivalEntity entity = VillageFestivalEntity.builder()
                .villageId(villageId)
                .title(request.title())
                .description(request.description())
                .startsAt(request.startsAt())
                .endsAt(request.endsAt())
                .bannerR2Key(request.bannerR2Key())
                .themeColorHex(request.themeColorHex())
                .status(initialStatus)
                .createdByUserId(actorUserId)
                .build();
        VillageFestivalEntity saved = festivalRepository.save(entity);

        auditLogService.record(
                AuditEventType.VILLAGE_FESTIVAL_CREATED.name(),
                actorUserId, null, null, null,
                null, null, null,
                "{\"villageId\":\"" + villageId
                        + "\",\"festivalId\":\"" + saved.getId()
                        + "\",\"status\":\"" + saved.getStatus()
                        + "\",\"startsAt\":\"" + saved.getStartsAt()
                        + "\",\"endsAt\":\"" + saved.getEndsAt() + "\"}"
        );
        log.info("Village festival created: villageId={} festivalId={} status={} by userId={}",
                villageId, saved.getId(), saved.getStatus(), actorUserId);

        return FestivalResponse.of(saved, null);
    }

    // ====================================================================
    // 更新
    // ====================================================================

    /**
     * お祭りを更新する（HEADMAN / ELDER のみ）。
     *
     * <p>{@code ENDED} / {@code CANCELLED} のお祭りは更新できない
     * （{@link VillageErrorCode#FESTIVAL_ALREADY_ENDED}）。</p>
     *
     * <p>期間は「指定されなかった項目は既存値」で再評価する。
     * 結果として {@code startsAt >= endsAt} になる更新は拒否。</p>
     */
    @Transactional
    public FestivalResponse updateFestival(UUID villageId,
                                           UUID festivalId,
                                           FestivalUpdateRequest request,
                                           Long actorUserId) {
        loadActiveVillage(villageId);
        requireHeadmanOrElder(villageId, actorUserId);

        VillageFestivalEntity entity = loadFestival(villageId, festivalId);

        if (entity.getStatus() == VillageFestivalStatus.ENDED
                || entity.getStatus() == VillageFestivalStatus.CANCELLED) {
            throw new BusinessException(VillageErrorCode.FESTIVAL_ALREADY_ENDED);
        }

        LocalDateTime newStartsAt = request.startsAt() != null ? request.startsAt() : entity.getStartsAt();
        LocalDateTime newEndsAt = request.endsAt() != null ? request.endsAt() : entity.getEndsAt();
        validatePeriod(newStartsAt, newEndsAt);

        if (request.themeColorHex() != null) {
            validateColor(request.themeColorHex());
        }

        if (request.title() != null) {
            entity.setTitle(request.title());
        }
        if (request.description() != null) {
            entity.setDescription(request.description());
        }
        if (request.startsAt() != null) {
            entity.setStartsAt(request.startsAt());
        }
        if (request.endsAt() != null) {
            entity.setEndsAt(request.endsAt());
        }
        if (request.bannerR2Key() != null) {
            entity.setBannerR2Key(request.bannerR2Key());
        }
        if (request.themeColorHex() != null) {
            entity.setThemeColorHex(request.themeColorHex());
        }

        VillageFestivalEntity saved = festivalRepository.save(entity);

        auditLogService.record(
                AuditEventType.VILLAGE_FESTIVAL_UPDATED.name(),
                actorUserId, null, null, null,
                null, null, null,
                "{\"villageId\":\"" + villageId
                        + "\",\"festivalId\":\"" + saved.getId() + "\"}"
        );
        log.info("Village festival updated: villageId={} festivalId={} by userId={}",
                villageId, saved.getId(), actorUserId);

        return FestivalResponse.of(saved, null);
    }

    // ====================================================================
    // 中止
    // ====================================================================

    /**
     * お祭りを中止（強制終了）する（HEADMAN / ELDER のみ）。
     *
     * <p>すでに {@code ENDED} / {@code CANCELLED} の場合は冪等に no-op。
     * 監査ログは新たに状態が変わった場合のみ記録する。</p>
     */
    @Transactional
    public FestivalResponse cancelFestival(UUID villageId, UUID festivalId, Long actorUserId) {
        loadActiveVillage(villageId);
        requireHeadmanOrElder(villageId, actorUserId);

        VillageFestivalEntity entity = loadFestival(villageId, festivalId);

        if (entity.getStatus() == VillageFestivalStatus.ENDED
                || entity.getStatus() == VillageFestivalStatus.CANCELLED) {
            // 冪等：既に終了/中止済みなら何もせず返す
            return FestivalResponse.of(entity, null);
        }

        entity.setStatus(VillageFestivalStatus.CANCELLED);
        VillageFestivalEntity saved = festivalRepository.save(entity);

        auditLogService.record(
                AuditEventType.VILLAGE_FESTIVAL_CANCELLED.name(),
                actorUserId, null, null, null,
                null, null, null,
                "{\"villageId\":\"" + villageId
                        + "\",\"festivalId\":\"" + saved.getId() + "\"}"
        );
        log.info("Village festival cancelled: villageId={} festivalId={} by userId={}",
                villageId, saved.getId(), actorUserId);

        return FestivalResponse.of(saved, null);
    }

    // ====================================================================
    // 一覧 / 詳細
    // ====================================================================

    /**
     * 村のお祭り一覧を取得する。
     *
     * @param villageId 村 ID
     * @param status    フィルタ用 status（null なら全状態）
     * @param pageable  ページ指定（null なら開始日時降順・先頭ページ）
     */
    public List<FestivalResponse> listFestivals(UUID villageId, VillageFestivalStatus status, Pageable pageable) {
        loadActiveVillage(villageId);
        Pageable resolved = resolvePageable(pageable);
        Page<VillageFestivalEntity> page = (status == null)
                ? festivalRepository.findByVillageIdAndDeletedAtIsNull(villageId, resolved)
                : festivalRepository.findByVillageIdAndStatusAndDeletedAtIsNull(villageId, status, resolved);
        return page.getContent().stream()
                .map(f -> FestivalResponse.of(f, null))
                .toList();
    }

    /**
     * 単一お祭りの詳細を取得する。
     */
    public FestivalResponse getFestival(UUID villageId, UUID festivalId) {
        loadActiveVillage(villageId);
        VillageFestivalEntity entity = loadFestival(villageId, festivalId);
        return FestivalResponse.of(entity, null);
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

    private VillageFestivalEntity loadFestival(UUID villageId, UUID festivalId) {
        VillageFestivalEntity entity = festivalRepository.findById(festivalId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.FESTIVAL_NOT_FOUND));
        // IDOR 対策: 村が違う・論理削除済みは 404
        if (!villageId.equals(entity.getVillageId()) || entity.getDeletedAt() != null) {
            throw new BusinessException(VillageErrorCode.FESTIVAL_NOT_FOUND);
        }
        return entity;
    }

    /**
     * 実行者が当該村の<strong>現役</strong> HEADMAN または ELDER であることを検証する。
     * いずれでもなければ {@link VillageErrorCode#MODERATION_FORBIDDEN}（403）。
     *
     * <p>「現役」の判定（退村済み {@code leftAt} / BAN 済み {@code bannedAt} の除外）は
     * {@code findActiveByVillageIdAndSubject} のクエリに委譲する（#2284 §12）。
     * 以前は BAN を検査しておらず、BAN された長老が祭りを作成・更新・中止できた。</p>
     */
    private void requireHeadmanOrElder(UUID villageId, Long actorUserId) {
        VillageMembershipEntity actor = membershipRepository
                .findActiveByVillageIdAndSubject(villageId, VillageSubjectType.USER, actorUserId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN));
        if (actor.getRole() != VillageRole.HEADMAN && actor.getRole() != VillageRole.ELDER) {
            throw new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN);
        }
    }

    private void validatePeriod(LocalDateTime startsAt, LocalDateTime endsAt) {
        if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
            throw new BusinessException(VillageErrorCode.FESTIVAL_INVALID_PERIOD);
        }
    }

    private void validateColor(String colorHex) {
        if (colorHex == null || colorHex.isBlank()) {
            return;
        }
        if (!HEX_COLOR_PATTERN.matcher(colorHex).matches()) {
            throw new BusinessException(VillageErrorCode.FESTIVAL_INVALID_COLOR);
        }
    }

    private Pageable resolvePageable(Pageable pageable) {
        if (pageable == null) {
            return PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "startsAt"));
        }
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
        }
        return pageable;
    }
}
