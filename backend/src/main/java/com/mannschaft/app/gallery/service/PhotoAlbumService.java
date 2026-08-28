package com.mannschaft.app.gallery.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.gallery.AlbumVisibility;
import com.mannschaft.app.gallery.GalleryErrorCode;
import com.mannschaft.app.gallery.GalleryMapper;
import com.mannschaft.app.gallery.dto.AlbumResponse;
import com.mannschaft.app.gallery.dto.CreateAlbumRequest;
import com.mannschaft.app.gallery.dto.UpdateAlbumRequest;
import com.mannschaft.app.gallery.entity.PhotoAlbumEntity;
import com.mannschaft.app.gallery.repository.PhotoAlbumRepository;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 写真アルバムサービス。アルバムのCRUD・検索を担当する。
 *
 * <p><b>F00 Phase E-5</b>: アルバム一覧取得（{@link #listAlbums}）と詳細取得（{@link #getAlbum}）に
 * {@link ContentVisibilityChecker} を統合し、旧来の可視性未適用状態を根治した。
 * {@link PhotoAlbumVisibilityResolver}（Phase D-β 実装済み）が
 * {@link AlbumVisibility} → {@link com.mannschaft.app.common.visibility.StandardVisibility} を
 * 変換し、呼び出し元ユーザーのメンバーシップに基づいてフィルタリングする。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PhotoAlbumService {

    private final PhotoAlbumRepository albumRepository;
    private final GalleryMapper galleryMapper;
    /** F00 Phase E-5: 可視性判定ファサード。 */
    private final ContentVisibilityChecker contentVisibilityChecker;
    /** 認可根治戦役 Wave3-B5: 書込CRUD（作成/更新/削除）の scope 認可用。 */
    private final AccessControlService accessControlService;
    /** CMP-028 Phase B: 可視レベル解決に用いる F00 メンバーシップ照会サービス。 */
    private final com.mannschaft.app.common.visibility.MembershipBatchQueryService membershipBatchQueryService;

    /**
     * アルバム一覧をページング取得する。
     *
     * <p><b>CMP-028 Phase B: 可視性の SQL 述語化（歯抜け根治）</b>: 旧実装は 1 ページ分を
     * 無条件取得してから {@link ContentVisibilityChecker#filterAccessible} でメモリ上
     * フィルタしており、非公開アルバムが混ざると要求件数より少ない件数しか返らない
     * 「ページング歯抜け」があった（AC-6）。総件数も上界近似だった（AC-7）。</p>
     *
     * <p>本メソッドは {@code MembershipBatchQueryService#resolveVisibleLevels} が返す
     * 可視 {@code StandardVisibility} 集合を
     * {@link com.mannschaft.app.common.visibility.mapping.AlbumVisibilityMapper#toFunctional}
     * で {@link AlbumVisibility} 集合へ逆写像し、SQL の {@code WHERE visibility IN (...)}
     * へ渡す（{@link AlbumVisibility} は 3 値のみで行依存値を持たないため歯抜けが
     * 数学的にゼロになる）。{@link AlbumVisibility} には {@code PUBLIC} 相当が無いため、
     * 逆写像結果が空集合になり得る（非所属・未認証）。その場合は SQL を発行せず
     * 空ページを返す（{@code IN ()} は不正 SQL のため）。</p>
     */
    public Page<AlbumResponse> listAlbums(Long teamId, Long organizationId, String query,
                                             LocalDate from, LocalDate to, String visibility,
                                             Pageable pageable) {
        Long viewerUserId = SecurityUtils.getCurrentUserIdOrNull();
        String scopeType = teamId != null ? "TEAM" : "ORGANIZATION";
        Long scopeId = teamId != null ? teamId : organizationId;
        com.mannschaft.app.common.visibility.ScopeKey scope =
                new com.mannschaft.app.common.visibility.ScopeKey(scopeType, scopeId);
        com.mannschaft.app.common.visibility.UserScopeRoleSnapshot snapshot =
                membershipBatchQueryService.snapshotForUser(viewerUserId, Set.of(scope), Set.of(scope));
        Set<com.mannschaft.app.common.visibility.StandardVisibility> visibleLevels =
                membershipBatchQueryService.resolveVisibleLevels(scope, snapshot);
        Set<AlbumVisibility> visibleVisibilities =
                com.mannschaft.app.common.visibility.mapping.AlbumVisibilityMapper.toFunctional(visibleLevels);

        if (visibleVisibilities.isEmpty()) {
            // 非所属・未認証: AlbumVisibility には PUBLIC 相当が無いため何も見えない（fail-closed）。
            // SQL の IN () は不正になるため発行せず空ページを返す。
            return Page.empty(pageable);
        }

        Page<PhotoAlbumEntity> page;
        if (query != null && !query.isBlank()) {
            if (teamId != null) {
                page = albumRepository.findByTeamIdAndTitleContainingAndVisibilityInOrderByEventDateDesc(
                        teamId, query, visibleVisibilities, pageable);
            } else {
                page = albumRepository.findByOrganizationIdAndTitleContainingAndVisibilityInOrderByEventDateDesc(
                        organizationId, query, visibleVisibilities, pageable);
            }
        } else {
            if (teamId != null) {
                page = albumRepository.findByTeamIdAndVisibilityInOrderByEventDateDesc(
                        teamId, visibleVisibilities, pageable);
            } else {
                page = albumRepository.findByOrganizationIdAndVisibilityInOrderByEventDateDesc(
                        organizationId, visibleVisibilities, pageable);
            }
        }

        // 第二の門（保険）: SQL 述語と F00 の判定が食い違った場合を検知する。通常は 1 件も落ちない。
        List<PhotoAlbumEntity> all = page.getContent();
        if (all.isEmpty()) {
            return page.map(galleryMapper::toAlbumResponse);
        }
        Set<Long> accessibleIds = contentVisibilityChecker.filterAccessible(
                ReferenceType.PHOTO_ALBUM,
                all.stream().map(PhotoAlbumEntity::getId).collect(Collectors.toSet()),
                viewerUserId);
        if (accessibleIds.size() == all.size()) {
            return page.map(galleryMapper::toAlbumResponse);
        }
        List<Long> divergentIds = all.stream()
                .map(PhotoAlbumEntity::getId)
                .filter(id -> !accessibleIds.contains(id))
                .toList();
        log.warn("アルバム一覧: SQL 述語と F00 可視性判定が乖離しました（fail-closed で除外）。"
                        + "teamId={}, organizationId={}, viewerUserId={}, divergentIds={}",
                teamId, organizationId, viewerUserId, divergentIds);
        List<PhotoAlbumEntity> filtered = all.stream()
                .filter(e -> accessibleIds.contains(e.getId()))
                .collect(Collectors.toList());
        return new PageImpl<>(
                filtered.stream().map(galleryMapper::toAlbumResponse).collect(Collectors.toList()),
                pageable,
                Math.max(0L, page.getTotalElements() - divergentIds.size()));
    }

    /**
     * アルバム詳細を取得する。
     *
     * <p><b>F00 Phase E-5</b>: {@link ContentVisibilityChecker#assertCanView(ReferenceType, Long, Long)}
     * 経由で可視性チェックを行う。閲覧不可の場合は {@link com.mannschaft.app.common.BusinessException} をスローする。</p>
     */
    public AlbumResponse getAlbum(Long albumId) {
        Long viewerUserId = SecurityUtils.getCurrentUserIdOrNull();
        contentVisibilityChecker.assertCanView(ReferenceType.PHOTO_ALBUM, albumId, viewerUserId);
        PhotoAlbumEntity entity = findAlbumOrThrow(albumId);
        return galleryMapper.toAlbumResponse(entity);
    }

    /**
     * アルバムを作成する。
     *
     * <p><b>認可根治戦役 Wave3-B5</b>: 作成先 scope（teamId/organizationId）の ADMIN/DEPUTY_ADMIN
     * のみ作成可（{@link AccessControlService#checkAdminOrAbove}）。従来は非会員でも
     * 任意チーム/組織にアルバムを作成できる無防備状態だった。</p>
     */
    @Transactional
    public AlbumResponse createAlbum(Long userId, CreateAlbumRequest request) {
        accessControlService.checkAdminOrAbove(userId,
                resolveScopeId(request.getTeamId(), request.getOrganizationId()),
                resolveScopeType(request.getTeamId()));

        AlbumVisibility visibility = request.getVisibility() != null
                ? AlbumVisibility.valueOf(request.getVisibility()) : AlbumVisibility.ALL_MEMBERS;
        Boolean allowMemberUpload = request.getAllowMemberUpload() != null
                ? request.getAllowMemberUpload() : false;
        Boolean allowDownload = request.getAllowDownload() != null
                ? request.getAllowDownload() : true;

        PhotoAlbumEntity entity = PhotoAlbumEntity.builder()
                .teamId(request.getTeamId())
                .organizationId(request.getOrganizationId())
                .title(request.getTitle())
                .description(request.getDescription())
                .eventDate(request.getEventDate())
                .visibility(visibility)
                .allowMemberUpload(allowMemberUpload)
                .allowDownload(allowDownload)
                .createdBy(userId)
                .build();

        PhotoAlbumEntity saved = albumRepository.save(entity);
        log.info("アルバム作成: albumId={}", saved.getId());
        return galleryMapper.toAlbumResponse(saved);
    }

    /**
     * アルバムを更新する。
     *
     * <p><b>認可根治戦役 Wave3-B5</b>: entity 由来 scope（teamId/organizationId）の
     * ADMIN/DEPUTY_ADMIN のみ更新可。id 指定エンドポイント（scope が path に無い）のため、
     * scope は必ず entity 側から解決する（digest 等の既存 ID-only ドメインと同じ手本）。</p>
     */
    @Transactional
    public AlbumResponse updateAlbum(Long albumId, Long userId, UpdateAlbumRequest request) {
        PhotoAlbumEntity entity = findAlbumOrThrow(albumId);
        accessControlService.checkAdminOrAbove(userId,
                resolveScopeId(entity.getTeamId(), entity.getOrganizationId()),
                resolveScopeType(entity.getTeamId()));

        AlbumVisibility visibility = request.getVisibility() != null
                ? AlbumVisibility.valueOf(request.getVisibility()) : entity.getVisibility();
        Boolean allowMemberUpload = request.getAllowMemberUpload() != null
                ? request.getAllowMemberUpload() : entity.getAllowMemberUpload();
        Boolean allowDownload = request.getAllowDownload() != null
                ? request.getAllowDownload() : entity.getAllowDownload();

        entity.update(request.getTitle(), request.getDescription(), request.getEventDate(),
                visibility, allowMemberUpload, allowDownload, request.getCoverPhotoId());

        PhotoAlbumEntity saved = albumRepository.save(entity);
        log.info("アルバム更新: albumId={}", albumId);
        return galleryMapper.toAlbumResponse(saved);
    }

    /**
     * アルバムを論理削除する。
     *
     * <p><b>認可根治戦役 Wave3-B5</b>: entity 由来 scope の ADMIN/DEPUTY_ADMIN のみ削除可。</p>
     */
    @Transactional
    public void deleteAlbum(Long albumId, Long userId) {
        PhotoAlbumEntity entity = findAlbumOrThrow(albumId);
        accessControlService.checkAdminOrAbove(userId,
                resolveScopeId(entity.getTeamId(), entity.getOrganizationId()),
                resolveScopeType(entity.getTeamId()));
        entity.softDelete();
        albumRepository.save(entity);
        log.info("アルバム削除: albumId={}", albumId);
    }

    /**
     * アルバムエンティティを取得する。存在しない場合は例外をスローする。
     */
    PhotoAlbumEntity findAlbumOrThrow(Long albumId) {
        return albumRepository.findById(albumId)
                .orElseThrow(() -> new BusinessException(GalleryErrorCode.ALBUM_NOT_FOUND));
    }

    /**
     * teamId/organizationId（片方のみ設定される想定）から scopeType 文字列を解決する
     * （認可根治戦役 Wave3-B5: {@code AccessControlService} 呼び出し共通ヘルパー）。
     */
    static String resolveScopeType(Long teamId) {
        return teamId != null ? "TEAM" : "ORGANIZATION";
    }

    /**
     * teamId/organizationId（片方のみ設定される想定）から scopeId を解決する。
     */
    static Long resolveScopeId(Long teamId, Long organizationId) {
        return teamId != null ? teamId : organizationId;
    }
}
