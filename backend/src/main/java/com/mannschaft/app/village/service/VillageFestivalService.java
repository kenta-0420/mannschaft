package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.FestivalCreateRequest;
import com.mannschaft.app.village.dto.FestivalResponse;
import com.mannschaft.app.village.dto.FestivalUpdateRequest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageFestivalEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageEventNotificationType;
import com.mannschaft.app.village.entity.enums.VillageFestivalStatus;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.event.VillageEventOccurredEvent;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageFestivalRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
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
import java.util.Map;
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
 *   <li>一覧・詳細取得: 村掲示板と<b>同一の閲覧認可</b>に従う。村の {@code bulletin_visibility} が
 *       {@code MEMBERS_ONLY}（既定値）なら村メンバーまたは SYSTEM_ADMIN のみ、{@code PUBLIC} なら
 *       ログイン済ユーザーなら誰でも参照できる。判定は
 *       {@link VillageBulletinAccessService#checkVillageBulletinViewAccess} に委譲する
 *       （村史 {@code VillageChronicleService} / 村ニュースレター {@code VillageNewsletterIssueService}
 *       と同じ方式に揃える）。</li>
 * </ul>
 *
 * <p>閲覧認可は本サービス（public 入口）で実施し、認可の所在を Service に一本化する。
 * Controller やバッチなど呼び出し元まかせの認可は作らない（別経路から呼ばれても認可が抜けないため）。</p>
 *
 * <p>村の生存判定（削除済み／凍結済み）も
 * {@link VillageBulletinAccessService#checkVillageBulletinViewAccess} 側の統一クエリに委ねる。
 * 同メソッドは削除・凍結を一律 {@link VillageErrorCode#VILLAGE_NOT_FOUND}（404）に畳んでおり、
 * 閲覧権限を持たない相手に村の状態を識別させない。読取経路で村を個別にロードして
 * 状態別のエラーコードを投げ分けると、お祭りの存在を秘匿しても村の状態が漏れる非対称が生じる。</p>
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
    private final VillageMembershipRepository membershipRepository;
    private final AuditLogService auditLogService;
    /** バナー画像の生 R2 キーを表示用の署名付き URL へ解決する共通部品（#2355 r2PublicUrl 根絶）。 */
    private final MediaUrlResolver mediaUrlResolver;
    /** 一覧・詳細の閲覧認可（村の bulletin_visibility ＋ 村メンバーシップ）を判定する。 */
    private final VillageBulletinAccessService bulletinAccessService;
    /** F17.2 Wave2 ①: 行事→村フィード自動還流イベントの発行（AFTER_COMMIT リスナーが購読・§3.3.1）。 */
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final VillageAccessGate accessGate;

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
        loadActiveVillage(villageId, actorUserId);
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

        // F17.2 Wave2 ①: 祭作成の還流（EVENT_CREATED・本体コミット後に AFTER_COMMIT リスナーが発火・§3.3.1）。
        eventPublisher.publishEvent(new VillageEventOccurredEvent(
                villageId, VillageEventNotificationType.EVENT_CREATED, saved.getId(),
                saved.getTitle(), "/villages/" + villageId + "/festivals/" + saved.getId()));

        return FestivalResponse.of(saved, null, mediaUrlResolver.resolve(saved.getBannerR2Key()));
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
        loadActiveVillage(villageId, actorUserId);
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

        return FestivalResponse.of(saved, null, mediaUrlResolver.resolve(saved.getBannerR2Key()));
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
        loadActiveVillage(villageId, actorUserId);
        requireHeadmanOrElder(villageId, actorUserId);

        VillageFestivalEntity entity = loadFestival(villageId, festivalId);

        if (entity.getStatus() == VillageFestivalStatus.ENDED
                || entity.getStatus() == VillageFestivalStatus.CANCELLED) {
            // 冪等：既に終了/中止済みなら何もせず返す
            return FestivalResponse.of(entity, null, mediaUrlResolver.resolve(entity.getBannerR2Key()));
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

        return FestivalResponse.of(saved, null, mediaUrlResolver.resolve(saved.getBannerR2Key()));
    }

    // ====================================================================
    // 一覧 / 詳細
    // ====================================================================

    /**
     * 村のお祭り一覧を取得する。
     *
     * <p>閲覧認可は村掲示板と同一（村の {@code bulletin_visibility} と村メンバーシップに従う）。
     * バナー画像の署名 URL を含むため、非メンバーへ祭り情報が漏れないよう
     * <b>データ取得より先に</b>認可を行う。</p>
     *
     * <p>村の生存判定は {@code checkVillageBulletinViewAccess} 側の統一クエリに委ねる
     * （削除・凍結とも 404）。ここで {@code loadActiveVillage} を別途呼ぶと凍結村にだけ
     * 異なるエラーコードが返り、閲覧権限の無い相手に村の状態を識別させてしまう。</p>
     *
     * @param villageId    村 ID
     * @param status       フィルタ用 status（null なら全状態）
     * @param pageable     ページ指定（null なら開始日時降順・先頭ページ）
     * @param actorUserId  閲覧しようとするログイン済ユーザー ID
     * @throws BusinessException 村が存在しない／削除済み／凍結済み（404）／閲覧権限が無い（403）
     */
    public List<FestivalResponse> listFestivals(UUID villageId,
                                                VillageFestivalStatus status,
                                                Pageable pageable,
                                                Long actorUserId) {
        bulletinAccessService.checkVillageBulletinViewAccess(villageId, actorUserId);
        Pageable resolved = resolvePageable(pageable);
        Page<VillageFestivalEntity> page = (status == null)
                ? festivalRepository.findByVillageIdAndDeletedAtIsNull(villageId, resolved)
                : festivalRepository.findByVillageIdAndStatusAndDeletedAtIsNull(villageId, status, resolved);
        List<VillageFestivalEntity> content = page.getContent();

        // 一覧では同一バナーキー（使い回し画像）が複数行に現れうるため、
        // 行ごとに resolve() を個別に呼ばず resolveAll() で一括解決してメモ化する（N+1 防止）。
        Map<String, String> bannerUrlsByKey = mediaUrlResolver.resolveAll(
                content.stream().map(VillageFestivalEntity::getBannerR2Key).toList());

        return content.stream()
                .map(f -> FestivalResponse.of(f, null, bannerUrlsByKey.get(f.getBannerR2Key())))
                .toList();
    }

    /**
     * 単一お祭りの詳細を取得する。
     *
     * <p>認可はお祭りの存在確認より<b>先に</b>行う。非メンバーには「その ID のお祭りが
     * 存在するか否か」も秘匿する必要があるため、順序を入れ替えてはならない
     * （{@code VillageChronicleService.getChronicle} と同じ理由）。</p>
     *
     * <p>村の生存判定も同様に {@code checkVillageBulletinViewAccess} 側へ委ねる
     * （削除・凍結とも 404）。お祭りの存在を秘匿しても村の状態が漏れては意味がないため、
     * 読取経路では村を個別にロードしない。</p>
     *
     * @param villageId    村 ID
     * @param festivalId   お祭り ID
     * @param actorUserId  閲覧しようとするログイン済ユーザー ID
     * @throws BusinessException 村が存在しない／削除済み／凍結済み（404）／閲覧権限が無い（403）／
     *                           お祭りが無い（404）
     */
    public FestivalResponse getFestival(UUID villageId, UUID festivalId, Long actorUserId) {
        bulletinAccessService.checkVillageBulletinViewAccess(villageId, actorUserId);
        VillageFestivalEntity entity = loadFestival(villageId, festivalId);
        return FestivalResponse.of(entity, null, mediaUrlResolver.resolve(entity.getBannerR2Key()));
    }

    // ====================================================================
    // 共通ヘルパ
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
