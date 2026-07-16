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

    /**
     * アルバム一覧をページング取得する。
     *
     * <p><b>F00 Phase E-5</b>: 旧来の visibility パラメータによる直接フィルタ（未実装・対応予定扱い）を廃止し、
     * {@link ContentVisibilityChecker#filterAccessible(ReferenceType, java.util.Collection, Long)}
     * 経由に一本化。ログイン中ユーザーの ID を {@link SecurityUtils#getCurrentUserIdOrNull()}
     * で取得し、未認証の場合は {@code null} を渡す（PUBLIC のみ通過）。
     * visibility クエリパラメータは後方互換のためシグネチャに残すが、判定は Resolver に委譲する。</p>
     */
    public Page<AlbumResponse> listAlbums(Long teamId, Long organizationId, String query,
                                             LocalDate from, LocalDate to, String visibility,
                                             Pageable pageable) {
        Page<PhotoAlbumEntity> page;
        if (query != null && !query.isBlank()) {
            if (teamId != null) {
                page = albumRepository.findByTeamIdAndTitleContainingOrderByEventDateDesc(teamId, query, pageable);
            } else {
                page = albumRepository.findByOrganizationIdAndTitleContainingOrderByEventDateDesc(organizationId, query, pageable);
            }
        } else {
            if (teamId != null) {
                page = albumRepository.findByTeamIdOrderByEventDateDesc(teamId, pageable);
            } else {
                page = albumRepository.findByOrganizationIdOrderByEventDateDesc(organizationId, pageable);
            }
        }

        // F00 Phase E-5: ContentVisibilityChecker 経由で可視性フィルタリング
        List<PhotoAlbumEntity> all = page.getContent();
        if (all.isEmpty()) {
            return page.map(galleryMapper::toAlbumResponse);
        }

        Long viewerUserId = SecurityUtils.getCurrentUserIdOrNull();
        Set<Long> accessibleIds = contentVisibilityChecker.filterAccessible(
                ReferenceType.PHOTO_ALBUM,
                all.stream().map(PhotoAlbumEntity::getId).collect(Collectors.toSet()),
                viewerUserId);

        List<PhotoAlbumEntity> filtered = all.stream()
                .filter(e -> accessibleIds.contains(e.getId()))
                .collect(Collectors.toList());

        return new PageImpl<>(
                filtered.stream().map(galleryMapper::toAlbumResponse).collect(Collectors.toList()),
                pageable,
                filtered.size());
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
