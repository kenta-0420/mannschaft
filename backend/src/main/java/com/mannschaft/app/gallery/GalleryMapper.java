package com.mannschaft.app.gallery;

import com.mannschaft.app.gallery.dto.AlbumResponse;
import com.mannschaft.app.gallery.dto.PhotoResponse;
import com.mannschaft.app.gallery.entity.PhotoAlbumEntity;
import com.mannschaft.app.gallery.entity.PhotoEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * ギャラリー機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface GalleryMapper {

    @Mapping(target = "visibility", expression = "java(entity.getVisibility().name())")
    AlbumResponse toAlbumResponse(PhotoAlbumEntity entity);

    List<AlbumResponse> toAlbumResponseList(List<PhotoAlbumEntity> entities);

    PhotoResponse toPhotoResponse(PhotoEntity entity);

    List<PhotoResponse> toPhotoResponseList(List<PhotoEntity> entities);
}
