package com.mannschaft.app.gallery;

import com.mannschaft.app.gallery.dto.AlbumResponse;
import com.mannschaft.app.gallery.dto.PhotoResponse;
import com.mannschaft.app.gallery.entity.PhotoAlbumEntity;
import com.mannschaft.app.gallery.entity.PhotoEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:11+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class GalleryMapperImpl implements GalleryMapper {

    @Override
    public AlbumResponse toAlbumResponse(PhotoAlbumEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long teamId = null;
        Long organizationId = null;
        String title = null;
        String description = null;
        Long coverPhotoId = null;
        LocalDate eventDate = null;
        Boolean allowMemberUpload = null;
        Boolean allowDownload = null;
        Integer photoCount = null;
        Long createdBy = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        teamId = entity.getTeamId();
        organizationId = entity.getOrganizationId();
        title = entity.getTitle();
        description = entity.getDescription();
        coverPhotoId = entity.getCoverPhotoId();
        eventDate = entity.getEventDate();
        allowMemberUpload = entity.getAllowMemberUpload();
        allowDownload = entity.getAllowDownload();
        photoCount = entity.getPhotoCount();
        createdBy = entity.getCreatedBy();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String visibility = entity.getVisibility().name();

        AlbumResponse albumResponse = new AlbumResponse( id, teamId, organizationId, title, description, coverPhotoId, eventDate, visibility, allowMemberUpload, allowDownload, photoCount, createdBy, createdAt, updatedAt );

        return albumResponse;
    }

    @Override
    public List<AlbumResponse> toAlbumResponseList(List<PhotoAlbumEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<AlbumResponse> list = new ArrayList<AlbumResponse>( entities.size() );
        for ( PhotoAlbumEntity photoAlbumEntity : entities ) {
            list.add( toAlbumResponse( photoAlbumEntity ) );
        }

        return list;
    }

    @Override
    public PhotoResponse toPhotoResponse(PhotoEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long albumId = null;
        String s3Key = null;
        String thumbnailS3Key = null;
        String originalFilename = null;
        String contentType = null;
        Long fileSize = null;
        Integer width = null;
        Integer height = null;
        String caption = null;
        LocalDateTime takenAt = null;
        Integer sortOrder = null;
        Long uploadedBy = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        albumId = entity.getAlbumId();
        s3Key = entity.getS3Key();
        thumbnailS3Key = entity.getThumbnailS3Key();
        originalFilename = entity.getOriginalFilename();
        contentType = entity.getContentType();
        fileSize = entity.getFileSize();
        width = entity.getWidth();
        height = entity.getHeight();
        caption = entity.getCaption();
        takenAt = entity.getTakenAt();
        sortOrder = entity.getSortOrder();
        uploadedBy = entity.getUploadedBy();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        PhotoResponse photoResponse = new PhotoResponse( id, albumId, s3Key, thumbnailS3Key, originalFilename, contentType, fileSize, width, height, caption, takenAt, sortOrder, uploadedBy, createdAt, updatedAt );

        return photoResponse;
    }

    @Override
    public List<PhotoResponse> toPhotoResponseList(List<PhotoEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<PhotoResponse> list = new ArrayList<PhotoResponse>( entities.size() );
        for ( PhotoEntity photoEntity : entities ) {
            list.add( toPhotoResponse( photoEntity ) );
        }

        return list;
    }
}
