package com.mannschaft.app.family;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.family.dto.CreateWallpaperRequest;
import com.mannschaft.app.family.dto.WallpaperResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 壁紙サービス。テンプレート壁紙の管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WallpaperService {

    private final TemplateWallpaperRepository templateWallpaperRepository;

    /**
     * チームで利用可能な壁紙一覧を取得する。
     *
     * @param templateSlug テンプレートスラッグ
     * @return 壁紙一覧
     */
    public ApiResponse<List<WallpaperResponse>> getAvailableWallpapers(String templateSlug) {
        List<TemplateWallpaperEntity> wallpapers = templateWallpaperRepository.findAvailableBySlug(templateSlug);
        return ApiResponse.of(wallpapers.stream().map(this::toResponse).toList());
    }

    /**
     * 管理画面用：全壁紙一覧を取得する。
     *
     * @return 壁紙一覧
     */
    public ApiResponse<List<WallpaperResponse>> getAllWallpapers() {
        List<TemplateWallpaperEntity> wallpapers = templateWallpaperRepository
                .findAllByOrderByTemplateSlugAscSortOrderAsc();
        return ApiResponse.of(wallpapers.stream().map(this::toResponse).toList());
    }

    /**
     * 壁紙を追加する（SYSTEM_ADMIN用）。
     *
     * @param request リクエスト
     * @return 追加された壁紙
     */
    @Transactional
    public ApiResponse<WallpaperResponse> createWallpaper(CreateWallpaperRequest request) {
        WallpaperCategory category = request.getCategory() != null
                ? WallpaperCategory.valueOf(request.getCategory().toUpperCase())
                : WallpaperCategory.DEFAULT;

        TemplateWallpaperEntity entity = TemplateWallpaperEntity.builder()
                .templateSlug(request.getTemplateSlug())
                .name(request.getName())
                .imageUrl(request.getImageUrl())
                .thumbnailUrl(request.getThumbnailUrl())
                .category(category)
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .build();

        return ApiResponse.of(toResponse(templateWallpaperRepository.save(entity)));
    }

    /**
     * 壁紙を削除する（SYSTEM_ADMIN用）。
     *
     * @param id 壁紙ID
     */
    @Transactional
    public void deleteWallpaper(Long id) {
        TemplateWallpaperEntity entity = templateWallpaperRepository.findById(id)
                .orElseThrow(() -> new BusinessException(FamilyErrorCode.FAMILY_020));
        templateWallpaperRepository.delete(entity);
    }

    private WallpaperResponse toResponse(TemplateWallpaperEntity entity) {
        return new WallpaperResponse(
                entity.getId(), entity.getTemplateSlug(), entity.getName(),
                entity.getImageUrl(), entity.getThumbnailUrl(),
                entity.getCategory().name(), entity.getSortOrder(),
                Boolean.TRUE.equals(entity.getIsActive())
        );
    }
}
