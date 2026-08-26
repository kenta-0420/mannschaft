package com.mannschaft.app.village.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.MonshoUploadUrlResponse;
import com.mannschaft.app.village.dto.VillageCreateRequest;
import com.mannschaft.app.village.dto.VillageResponse;
import com.mannschaft.app.village.dto.VillageSearchResponse;
import com.mannschaft.app.village.dto.VillageUpdateRequest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageBulletinVisibility;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.UserVillagePinRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import com.mannschaft.app.village.repository.VillageSearchRepository;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 村本体サービス（F17.1 Phase 1 §4.1 / §4.2）。
 *
 * <p>村の CRUD・検索・凍結を提供する。村作成は SYSTEM_ADMIN（運営権限）に制限する。
 * 一般ユーザーは {@code village_creation_requests} 経由でしか作れない（B5 担当範囲）。
 * 更新・削除は HEADMAN または SYSTEM_ADMIN のみ。</p>
 *
 * <h2>セキュリティ</h2>
 * <ul>
 *   <li>UNLISTED 村は村人 / SYSTEM_ADMIN のみ取得可。非村人には不在と同じ
 *       {@link VillageErrorCode#VILLAGE_NOT_FOUND} を返す（存在秘匿）</li>
 *   <li>archived / deleted 状態の村は 404 を返す（IDOR 対策）</li>
 *   <li>レスポンスから個人特定情報（user_id 等）を排除</li>
 * </ul>
 *
 * <h2>レートリミット</h2>
 * <p>村作成は 3 件/日/ユーザー（設計書 §6.4）。本クラスでは作成時に
 * 当日 {@code created_by_user_id} で集計した件数で検証する（シンプル実装）。</p>
 *
 * <h2>原則準拠</h2>
 * <p>{@code @Transactional} は village ドメイン内に閉じる。クロスドメイン Repository
 * を呼び出すのは {@code AccessControlService.isSystemAdmin}（SYSTEM_ADMIN 判定のみ）に限定し、
 * 副作用書き込みは行わない。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VillageService {

    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9-]{3,40}$");

    /** 村作成レートリミット（件/日/ユーザー） */
    private static final int VILLAGE_CREATION_DAILY_LIMIT = 3;

    /** 村紋（monsho）アップロードで許可する MIME タイプ（#2355）。 */
    private static final java.util.Set<String> ALLOWED_MONSHO_MIME =
            java.util.Set.of("image/jpeg", "image/png", "image/webp");

    /** 村紋アップロードのファイルサイズ上限（バイト）。ProfileMedia ICON=5MB を手本（#2355）。 */
    private static final long MONSHO_MAX_BYTES = 5L * 1024 * 1024;

    /** 村紋アップロード用 presigned URL の有効期限（秒）（#2355）。 */
    private static final long MONSHO_UPLOAD_TTL_SECONDS = 600L;

    private final VillageRepository villageRepository;
    private final VillageAccessGate accessGate;
    private final VillageSearchRepository villageSearchRepository;
    private final VillageMembershipRepository membershipRepository;
    private final UserVillagePinRepository pinRepository;
    private final AccessControlService accessControlService;
    private final R2StorageService r2StorageService;
    /** 生の R2 キーを表示用の署名付き URL へ解決する共通部品（#2355 / VillagePinService と同方式）。 */
    private final MediaUrlResolver mediaUrlResolver;

    // ─────────────────────────────────────────────
    // 作成
    // ─────────────────────────────────────────────

    /**
     * 村を作成する。SYSTEM_ADMIN（運営権限）が必要。
     *
     * <p>一般ユーザーがこの API を直接叩いた場合、{@link VillageErrorCode#VILLAGE_CREATE_FORBIDDEN}
     * を返す。一般ユーザーは B5 で実装される村作成申請 API 経由で作成する。</p>
     */
    @Transactional
    public VillageResponse create(VillageCreateRequest req, Long requesterUserId) {
        // 運営権限チェック（CLAUDE.md 原則 5 注意: AccessControlService は読取のみ・副作用なし）
        if (!accessControlService.isSystemAdmin(requesterUserId)) {
            throw new BusinessException(VillageErrorCode.VILLAGE_CREATE_FORBIDDEN);
        }

        validateSlug(req.slug());

        // 一意性チェック
        if (villageRepository.existsBySlug(req.slug())) {
            throw new BusinessException(VillageErrorCode.VILLAGE_SLUG_TAKEN);
        }
        if (villageRepository.existsByName(req.name())) {
            throw new BusinessException(VillageErrorCode.VILLAGE_NAME_TAKEN);
        }

        // レートリミット: 当日作成件数（運営でも保険として制限）
        long todayCount = countCreatedByUserToday(requesterUserId);
        if (todayCount >= VILLAGE_CREATION_DAILY_LIMIT) {
            throw new BusinessException(VillageErrorCode.CREATION_REQUEST_THROTTLED);
        }

        VillageEntity entity = VillageEntity.builder()
                .slug(req.slug())
                .name(req.name())
                .description(req.description())
                .type(req.type())
                .joinPolicy(req.joinPolicy())
                .visibility(req.visibility())
                .bulletinVisibility(req.bulletinVisibility() != null
                        ? req.bulletinVisibility()
                        : VillageBulletinVisibility.MEMBERS_ONLY)
                .category(req.category())
                .guidelineMd(req.guidelineMd())
                .memberCountCache(0L)
                .createdByUserId(requesterUserId)
                .build();

        entity = villageRepository.save(entity);
        log.info("村作成: id={}, slug={}, type={}, byUser={}",
                entity.getId(), entity.getSlug(), entity.getType(), requesterUserId);

        return toResponse(entity, requesterUserId, /* includePrivateView= */ true);
    }

    // ─────────────────────────────────────────────
    // 取得
    // ─────────────────────────────────────────────

    /**
     * 村詳細を取得する。
     *
     * <p>UNLISTED 村は村人または SYSTEM_ADMIN のみアクセス可。
     * 削除済み / 凍結済み村は 404 を返す（IDOR 対策）。</p>
     *
     * <p>UNLISTED 村への非村人アクセスは<b>不在と完全に同じ
     * {@link VillageErrorCode#VILLAGE_NOT_FOUND}</b>で応答する。UNLISTED は検索結果から
     * 意図的に除外され「存在を隠す」設計であり、403 を返すと不在の 404 との差で村 ID の実在が
     * 漏れる（存在オラクル）。かつては専用コード {@code VILLAGE_002} を投げていたが、
     * ステータスが 404 で揃っていても<b>応答本文の {@code error.code}</b> が不在時と違うため、
     * 本文だけで実在を判別できてしまっていた。相性プロファイル・村憲章など兄弟 EP も同じく秘匿する。</p>
     */
    public VillageResponse get(UUID villageId, Long requesterUserId) {
        VillageEntity entity = findActiveOrThrow(villageId, requesterUserId);

        boolean isMember = isMember(villageId, requesterUserId);
        boolean isAdmin = accessControlService.isSystemAdmin(requesterUserId);

        // UNLISTED 村の閲覧制限（不在と完全に同じ VILLAGE_NOT_FOUND で存在ごと秘匿）
        if (entity.getVisibility() == VillageVisibility.UNLISTED && !isMember && !isAdmin) {
            // 不在側のコードそのものを投げる。専用コード（VILLAGE_002）だとステータスが 404 で揃っていても
            // 応答本文の error.code が不在時と違い、本文だけで実在が判別できてしまう。
            throw new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
        }

        return toResponse(entity, requesterUserId, true);
    }

    // ─────────────────────────────────────────────
    // 更新
    // ─────────────────────────────────────────────

    /**
     * 村を更新する。HEADMAN または SYSTEM_ADMIN のみ。
     */
    @Transactional
    public VillageResponse update(UUID villageId, VillageUpdateRequest req,
                                  Long requesterUserId, @Nullable Long expectedVersion) {
        VillageEntity entity = findActiveOrThrow(villageId, requesterUserId);
        requireHeadmanOrSystemAdmin(entity, requesterUserId);

        if (expectedVersion != null && !Objects.equals(entity.getVersion(), expectedVersion)) {
            throw new ObjectOptimisticLockingFailureException(VillageEntity.class, villageId);
        }

        // 名称の一意性チェック（変更時のみ）
        if (req.name() != null && !req.name().equals(entity.getName())) {
            if (villageRepository.existsByName(req.name())) {
                throw new BusinessException(VillageErrorCode.VILLAGE_NAME_TAKEN);
            }
            entity.setName(req.name());
        }
        if (req.description() != null) entity.setDescription(req.description());
        if (req.joinPolicy() != null) entity.setJoinPolicy(req.joinPolicy());
        if (req.visibility() != null) entity.setVisibility(req.visibility());
        if (req.bulletinVisibility() != null) entity.setBulletinVisibility(req.bulletinVisibility());
        if (req.category() != null) entity.setCategory(req.category());
        if (req.iconR2Key() != null) entity.setIconR2Key(req.iconR2Key());
        if (req.coverR2Key() != null) entity.setCoverR2Key(req.coverR2Key());
        if (req.guidelineMd() != null) entity.setGuidelineMd(req.guidelineMd());

        entity = villageRepository.save(entity);
        log.info("村更新: id={}, byUser={}", entity.getId(), requesterUserId);

        return toResponse(entity, requesterUserId, true);
    }

    // ─────────────────────────────────────────────
    // 論理削除
    // ─────────────────────────────────────────────

    /**
     * 村を論理削除する。HEADMAN または SYSTEM_ADMIN のみ。
     */
    @Transactional
    public void softDelete(UUID villageId, Long requesterUserId) {
        VillageEntity entity = findActiveOrThrow(villageId, requesterUserId);
        requireHeadmanOrSystemAdmin(entity, requesterUserId);

        entity.setDeletedAt(LocalDateTime.now());
        villageRepository.save(entity);
        log.info("村削除（論理）: id={}, byUser={}", villageId, requesterUserId);
    }

    // ─────────────────────────────────────────────
    // 凍結（運営権限）
    // ─────────────────────────────────────────────

    /**
     * 村を凍結する。SYSTEM_ADMIN のみ。
     *
     * <p>{@code archivedAt} を設定するだけで、データ自体は保持する。</p>
     */
    @Transactional
    public void archive(UUID villageId, Long requesterUserId, @Nullable String reason) {
        if (!accessControlService.isSystemAdmin(requesterUserId)) {
            throw new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN);
        }

        // 存在確認・可視性判定は VillageAccessGate に一元化する。
        // ここは SYSTEM_ADMIN しか到達しない（直前で MODERATION_FORBIDDEN 済み）ため可視性ゲートは素通りし、
        // 従来どおり「不在／削除済み=404・凍結済み=409」の応答がそのまま保たれる。
        VillageEntity entity = accessGate.loadActiveVillage(villageId, requesterUserId);

        entity.setArchivedAt(LocalDateTime.now());
        villageRepository.save(entity);
        log.info("村凍結: id={}, byUser={}, reason={}", villageId, requesterUserId, reason);
    }

    // ─────────────────────────────────────────────
    // 村紋（Monsho）更新／削除（F17 Phase 2 U7）
    // ─────────────────────────────────────────────

    /**
     * 村紋 R2 キーを更新する。HEADMAN または SYSTEM_ADMIN のみ。
     *
     * <p>R2 への実体アップロードはクライアント側で別途完了している想定（プリサインド URL 経由）。
     * 本メソッドは {@code villages.monsho_r2_key} カラムの値を新キーに差し替える。</p>
     *
     * @param villageId        対象村
     * @param r2Key            新しい R2 オブジェクトキー
     * @param requesterUserId  実行者ユーザー ID
     * @return 更新後の村エンティティ
     */
    @Transactional
    public VillageEntity updateMonsho(UUID villageId, String r2Key, Long requesterUserId) {
        VillageEntity entity = findActiveOrThrow(villageId, requesterUserId);
        requireHeadmanOrSystemAdmin(entity, requesterUserId);

        entity.setMonshoR2Key(r2Key);
        entity = villageRepository.save(entity);
        log.info("村紋更新: villageId={}, byUser={}", villageId, requesterUserId);
        return entity;
    }

    /**
     * 村紋を削除する（{@code monsho_r2_key} を NULL にクリア）。HEADMAN または SYSTEM_ADMIN のみ。
     *
     * <p>冪等: 既に NULL でも例外は投げず、現状の Entity をそのまま返す。
     * R2 上のオブジェクト削除自体は本メソッドの責務外（運用バッチ等で実施）。</p>
     */
    @Transactional
    public VillageEntity deleteMonsho(UUID villageId, Long requesterUserId) {
        VillageEntity entity = findActiveOrThrow(villageId, requesterUserId);
        requireHeadmanOrSystemAdmin(entity, requesterUserId);

        if (entity.getMonshoR2Key() == null) {
            return entity; // 冪等
        }
        entity.setMonshoR2Key(null);
        entity = villageRepository.save(entity);
        log.info("村紋削除: villageId={}, byUser={}", villageId, requesterUserId);
        return entity;
    }

    /**
     * 村紋（monsho）アップロード用の presigned PUT URL を発行する（F17 Phase 2 U7 / #2355）。
     *
     * <p>HEADMAN または SYSTEM_ADMIN のみ実行可能。MIME タイプ（image/jpeg / image/png / image/webp）と
     * ファイルサイズ上限を検証したうえで、R2 キー規約
     * {@code village/{villageId}/monsho/{UUID.randomUUID()}.{ext}} に従いキーを組み立て、
     * {@code r2StorageService.generateUploadUrl} で署名付き PUT URL を払い出す。</p>
     *
     * <p><strong>検証順序（IDOR 配慮）:</strong> 認可（村の存在確認 → HEADMAN/SYSTEM_ADMIN 判定）を
     * MIME/サイズ検証より必ず先に行う。これにより不正入力でも他人の村の存在有無を漏らさない。</p>
     *
     * <p><strong>読取との差異:</strong> 本メソッドは PUT アップロード用の presigned URL を発行するのみ。
     * 返却する {@code r2Key} は生キーであり、本メソッド自身は読取（表示）側の署名 URL 化（presigned GET）を
     * 行わない。読取は別途 {@code MediaUrlResolver} が村取得 API（{@code monshoUrl} 等）で presigned GET URL
     * として解決して返す。FE は署名 URL をそのまま {@code <img src>} に渡すのみで、公開 URL 組み立ては行わない。</p>
     *
     * @param villageId       対象村
     * @param contentType     アップロードする画像の Content-Type
     * @param fileSize        アップロードする画像のバイト数
     * @param requesterUserId 実行者ユーザー ID
     * @return presigned PUT URL / R2 キー / 有効期限（秒）
     */
    @Transactional(readOnly = true)
    public MonshoUploadUrlResponse generateMonshoUploadUrl(
            UUID villageId, String contentType, long fileSize, Long requesterUserId) {
        // 1〜2. 認可を先に（IDOR 配慮: 不正入力でも村の存在有無を漏らさない）
        VillageEntity entity = findActiveOrThrow(villageId, requesterUserId);
        requireHeadmanOrSystemAdmin(entity, requesterUserId);

        // 3. MIME 検証
        if (contentType == null || !ALLOWED_MONSHO_MIME.contains(contentType)) {
            throw new BusinessException(VillageErrorCode.VILLAGE_FIELD_INVALID);
        }

        // 4. サイズ検証（0 以下・上限超過は不正）
        if (fileSize <= 0 || fileSize > MONSHO_MAX_BYTES) {
            throw new BusinessException(VillageErrorCode.VILLAGE_FIELD_INVALID);
        }

        // 5〜6. R2 キー組み立て（village/{villageId}/monsho/{uuid}.{ext}）
        String ext = resolveMonshoExtension(contentType);
        String r2Key = String.format("village/%s/monsho/%s.%s", villageId, UUID.randomUUID(), ext);

        // 7. presigned PUT URL 発行（TTL=600 秒）
        PresignedUploadResult result = r2StorageService.generateUploadUrl(
                r2Key, contentType, Duration.ofSeconds(MONSHO_UPLOAD_TTL_SECONDS));

        log.info("村紋アップロードURL発行: villageId={}, byUser={}", villageId, requesterUserId);

        // 8. レスポンス組み立て
        return new MonshoUploadUrlResponse(
                result.uploadUrl(), result.s3Key(), result.expiresInSeconds());
    }

    /**
     * 村紋の Content-Type から拡張子を解決する（#2355）。
     * 許可済み MIME のみ呼ばれる前提（未知の型は呼び出し側で 400 済み）。
     */
    private String resolveMonshoExtension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> throw new BusinessException(VillageErrorCode.VILLAGE_FIELD_INVALID);
        };
    }

    // ─────────────────────────────────────────────
    // 検索
    // ─────────────────────────────────────────────

    /**
     * 村検索。PUBLIC かつ非削除・非凍結のみ返す。
     */
    public VillageSearchResponse search(@Nullable String q, @Nullable String category,
                                        @Nullable VillageType type, int page, int size,
                                        Long requesterUserId) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));

        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "memberCountCache")
                        .and(Sort.by(Sort.Direction.DESC, "createdAt")));

        Specification<VillageEntity> spec = VillageSearchSpecifications.searchable();
        Specification<VillageEntity> textSpec = VillageSearchSpecifications.textContains(q);
        if (textSpec != null) {
            spec = spec.and(textSpec);
        }
        Specification<VillageEntity> catSpec = VillageSearchSpecifications.categoryEquals(category);
        if (catSpec != null) {
            spec = spec.and(catSpec);
        }
        Specification<VillageEntity> typeSpec = VillageSearchSpecifications.typeEquals(type);
        if (typeSpec != null) {
            spec = spec.and(typeSpec);
        }

        Page<VillageEntity> result = villageSearchRepository.findAll(spec, pageable);

        // 一覧では同一キー（例: 同一運営が使い回す村アイコン）が複数行に現れうるため、
        // 行ごとに resolve() を個別に呼ばず resolveAll() で一括解決してメモ化する（N+1 防止 / AC-7）。
        List<String> allKeys = result.getContent().stream()
                .flatMap(v -> java.util.stream.Stream.of(
                        v.getIconR2Key(), v.getCoverR2Key(), v.getMonshoR2Key()))
                .toList();
        java.util.Map<String, String> resolvedUrls = mediaUrlResolver.resolveAll(allKeys);

        List<VillageResponse> items = result.getContent().stream()
                .map(v -> toResponse(v, requesterUserId, false,
                        resolvedUrls.get(v.getIconR2Key()),
                        resolvedUrls.get(v.getCoverR2Key()),
                        resolvedUrls.get(v.getMonshoR2Key())))
                .toList();

        return VillageSearchResponse.builder()
                .content(items)
                .totalElements(result.getTotalElements())
                .page(safePage)
                .size(safeSize)
                .build();
    }

    // ─────────────────────────────────────────────
    // 内部ヘルパー
    // ─────────────────────────────────────────────

    /**
     * 稼働中かつ実行者に可視な村を取得する。存在確認と可視性判定は
     * {@link VillageAccessGate} に一元化する。
     *
     * <p>従来の実体 {@code findByIdAndDeletedAtIsNullAndArchivedAtIsNull} は不在・削除済み・凍結済みを
     * まとめて {@code VILLAGE_NOT_FOUND} に畳んでいたため、同じく凍結も 404 に畳む
     * {@link VillageAccessGate#loadReadableVillage} へ委譲して挙動を揃える。</p>
     *
     * <p>これにより、後段の {@link #requireHeadmanOrSystemAdmin}（403）へ進む前に
     * 非公開(UNLISTED)村の非村人が 404 で弾かれ、「不在なら 404 ／実在すれば 403」の
     * 応答差から村の存在が漏れる経路（存在オラクル）が塞がる。
     * PUBLIC 村はゲートを素通りするため、非村人は従来どおり 403 のままである。</p>
     */
    private VillageEntity findActiveOrThrow(UUID villageId, Long requesterUserId) {
        return accessGate.loadReadableVillage(villageId, requesterUserId);
    }

    /**
     * <b>現役</b>の HEADMAN（退村・BAN 済みでない）または SYSTEM_ADMIN であることを要求する。
     * 違反時は 403。
     *
     * <p>「現役であること」の判定は村ドメインの正準述語
     * {@link VillageMembershipRepository#findActiveByVillageIdAndSubject} に委譲し、
     * 兄弟のモデレーション系ガード（{@code VillageReportService.requireModerator} 等）と
     * 判定基準を揃える（#2284 §12）。</p>
     */
    private void requireHeadmanOrSystemAdmin(VillageEntity village, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        Optional<VillageMembershipEntity> m = membershipRepository
                .findActiveByVillageIdAndSubject(
                        village.getId(), VillageSubjectType.USER, userId);
        if (m.isEmpty() || m.get().getRole() != VillageRole.HEADMAN) {
            throw new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN);
        }
    }

    private void validateSlug(String slug) {
        if (slug == null || !SLUG_PATTERN.matcher(slug).matches()) {
            throw new BusinessException(VillageErrorCode.VILLAGE_SLUG_INVALID);
        }
    }

    /** 当日その作成者ユーザーが作った村件数（archived/deleted 含む全レコード）。 */
    private long countCreatedByUserToday(Long userId) {
        LocalDateTime startOfDay = LocalDateTime.now().toLocalDate().atStartOfDay();
        Specification<VillageEntity> spec = (root, q, cb) -> cb.and(
                cb.equal(root.get("createdByUserId"), userId),
                cb.greaterThanOrEqualTo(root.get("createdAt"), startOfDay)
        );
        return villageSearchRepository.count(spec);
    }

    /**
     * 呼び出しユーザーが当該村の<b>現役</b>メンバー（退村・BAN 済みでない）かを判定する。
     *
     * <p>判定は村ドメインの正準述語
     * {@link VillageMembershipRepository#findActiveByVillageIdAndSubject} に委譲し、
     * 兄弟の認可ヘルパ（{@code requireModerator} 系）と判定基準を揃える（#2284 §12）。</p>
     */
    private boolean isMember(UUID villageId, Long userId) {
        if (userId == null) {
            return false;
        }
        return membershipRepository
                .findActiveByVillageIdAndSubject(villageId, VillageSubjectType.USER, userId)
                .isPresent();
    }

    private boolean isPinned(UUID villageId, Long userId) {
        if (userId == null) {
            return false;
        }
        return pinRepository.findByUserIdAndVillageId(userId, villageId).isPresent();
    }

    /**
     * {@link VillageEntity} を {@link VillageResponse} に変換する（単票用: create/get/update）。
     *
     * <p>icon/cover/monsho の3キーを {@link MediaUrlResolver#resolve} で個別に署名 URL 解決する。
     * 一覧（{@link #search}）は行数分の presign を避けるため、事前に {@code resolveAll} で
     * バッチ解決した結果を {@link #toResponse(VillageEntity, Long, boolean, String, String, String)}
     * へ直接渡す別経路を使う。</p>
     *
     * @param includePrivateView 詳細表示モード（{@code guidelineMd} などを返す）かどうか。
     *                           検索結果モードでは false。
     */
    private VillageResponse toResponse(VillageEntity v, Long requesterUserId, boolean includePrivateView) {
        String iconUrl = mediaUrlResolver.resolve(v.getIconR2Key());
        String coverUrl = mediaUrlResolver.resolve(v.getCoverR2Key());
        String monshoUrl = mediaUrlResolver.resolve(v.getMonshoR2Key());
        return toResponse(v, requesterUserId, includePrivateView, iconUrl, coverUrl, monshoUrl);
    }

    /**
     * {@link VillageEntity} を {@link VillageResponse} に変換する（画像 URL 解決済みを受け取る版）。
     *
     * <p>{@link #search} が {@code resolveAll} で一括解決した結果をここへ渡すことで、
     * 同一 R2 キーを共有する行でも presign が行数分走らないようにする（N+1 防止 / AC-7）。</p>
     */
    private VillageResponse toResponse(VillageEntity v, Long requesterUserId, boolean includePrivateView,
                                        String iconUrl, String coverUrl, String monshoUrl) {
        boolean isMember = isMember(v.getId(), requesterUserId);
        boolean isPinned = isPinned(v.getId(), requesterUserId);
        VillageRole myRole = null;
        if (isMember) {
            myRole = membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                            v.getId(), VillageSubjectType.USER, requesterUserId)
                    .map(VillageMembershipEntity::getRole)
                    .orElse(null);
        }
        return VillageResponse.builder()
                .id(v.getId())
                .slug(v.getSlug())
                .name(v.getName())
                .description(v.getDescription())
                .type(v.getType())
                .joinPolicy(v.getJoinPolicy())
                .visibility(v.getVisibility())
                .bulletinVisibility(v.getBulletinVisibility())
                .category(v.getCategory())
                .iconUrl(iconUrl)
                .coverUrl(coverUrl)
                .monshoUrl(monshoUrl)
                .guidelineMd(includePrivateView ? v.getGuidelineMd() : null)
                .memberCount(v.getMemberCountCache() != null ? v.getMemberCountCache() : 0L)
                .isOfficial(v.getType() == VillageType.OFFICIAL)
                .isMember(isMember)
                .isPinned(isPinned)
                .myRole(myRole)
                .archivedAt(v.getArchivedAt())
                .createdAt(v.getCreatedAt())
                .updatedAt(v.getUpdatedAt())
                .version(v.getVersion())
                .build();
    }
}
