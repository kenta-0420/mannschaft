package com.mannschaft.app.gallery.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.gallery.AlbumVisibility;
import com.mannschaft.app.gallery.GalleryErrorCode;
import com.mannschaft.app.gallery.GalleryMapper;
import com.mannschaft.app.gallery.dto.AlbumResponse;
import com.mannschaft.app.gallery.dto.CreateAlbumRequest;
import com.mannschaft.app.gallery.dto.UpdateAlbumRequest;
import com.mannschaft.app.gallery.entity.PhotoAlbumEntity;
import com.mannschaft.app.gallery.repository.PhotoAlbumRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 写真アルバムサービス。アルバムのCRUD・検索を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PhotoAlbumService {

    private final PhotoAlbumRepository albumRepository;
    private final GalleryMapper galleryMapper;

    /**
     * アルバム一覧をページング取得する。
     */
    public Page<AlbumResponse> listAlbums(Long teamId, Long organizationId, String query, Pageable pageable) {
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
        return page.map(galleryMapper::toAlbumResponse);
    }

    /**
     * アルバム詳細を取得する。
     */
    public AlbumResponse getAlbum(Long albumId) {
        PhotoAlbumEntity entity = findAlbumOrThrow(albumId);
        return galleryMapper.toAlbumResponse(entity);
    }

    /**
     * アルバムを作成する。
     */
    @Transactional
    public AlbumResponse createAlbum(Long userId, CreateAlbumRequest request) {
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
     */
    @Transactional
    public AlbumResponse updateAlbum(Long albumId, UpdateAlbumRequest request) {
        PhotoAlbumEntity entity = findAlbumOrThrow(albumId);

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
     */
    @Transactional
    public void deleteAlbum(Long albumId) {
        PhotoAlbumEntity entity = findAlbumOrThrow(albumId);
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
}
