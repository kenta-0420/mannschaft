package com.mannschaft.app.family.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.family.FamilyErrorCode;
import com.mannschaft.app.family.WallpaperCategory;
import com.mannschaft.app.family.dto.CreateWallpaperRequest;
import com.mannschaft.app.family.dto.WallpaperResponse;
import com.mannschaft.app.family.entity.TemplateWallpaperEntity;
import com.mannschaft.app.family.repository.TemplateWallpaperRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WallpaperService {

    private final TemplateWallpaperRepository templateWallpaperRepository;

    public ApiResponse<List<WallpaperResponse>> getAvailableWallpapers(String templateSlug) {
        List<TemplateWallpaperEntity> wallpapers = templateWallpaperRepository.findAvailableBySlug(templateSlug);
        return ApiResponse.of(wallpapers.stream().map(this::toResponse).toList());
    }

    public ApiResponse<List<WallpaperResponse>> getAllWallpapers() {
        List<TemplateWallpaperEntity> wallpapers = templateWallpaperRepository.findAllByOrderByTemplateSlugAscSortOrderAsc();
        return ApiResponse.of(wallpapers.stream().map(this::toResponse).toList());
    }

    @Transactional
    public ApiResponse<WallpaperResponse> createWallpaper(CreateWallpaperRequest request) {
        WallpaperCategory category = request.getCategory() != null
                ? WallpaperCategory.valueOf(request.getCategory().toUpperCase()) : WallpaperCategory.DEFAULT;
        TemplateWallpaperEntity entity = TemplateWallpaperEntity.builder()
                .templateSlug(request.getTemplateSlug()).name(request.getName())
                .imageUrl(request.getImageUrl()).thumbnailUrl(request.getThumbnailUrl())
                .category(category).sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0).build();
        return ApiResponse.of(toResponse(templateWallpaperRepository.save(entity)));
    }

    @Transactional
    public void deleteWallpaper(Long id) {
        TemplateWallpaperEntity entity = templateWallpaperRepository.findById(id)
                .orElseThrow(() -> new BusinessException(FamilyErrorCode.FAMILY_020));
        templateWallpaperRepository.delete(entity);
    }

    private WallpaperResponse toResponse(TemplateWallpaperEntity entity) {
        return new WallpaperResponse(entity.getId(), entity.getTemplateSlug(), entity.getName(),
                entity.getImageUrl(), entity.getThumbnailUrl(), entity.getCategory().name(),
                entity.getSortOrder(), Boolean.TRUE.equals(entity.getIsActive()));
    }
}
