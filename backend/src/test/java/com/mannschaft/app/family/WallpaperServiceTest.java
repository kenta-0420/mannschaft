package com.mannschaft.app.family;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.family.dto.CreateWallpaperRequest;
import com.mannschaft.app.family.dto.WallpaperResponse;
import com.mannschaft.app.family.entity.TemplateWallpaperEntity;
import com.mannschaft.app.family.repository.TemplateWallpaperRepository;
import com.mannschaft.app.family.service.WallpaperService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("WallpaperService 単体テスト")
class WallpaperServiceTest {

    @Mock private TemplateWallpaperRepository templateWallpaperRepository;
    @InjectMocks private WallpaperService service;

    @Nested
    @DisplayName("createWallpaper")
    class CreateWallpaper {

        @Test
        @DisplayName("正常系: 壁紙が作成される")
        void 作成_正常_保存() {
            // Given
            TemplateWallpaperEntity saved = TemplateWallpaperEntity.builder()
                    .templateSlug("family").name("桜")
                    .imageUrl("https://example.com/sakura.jpg")
                    .category(WallpaperCategory.DEFAULT).sortOrder(0).build();
            given(templateWallpaperRepository.save(any(TemplateWallpaperEntity.class))).willReturn(saved);

            CreateWallpaperRequest req = new CreateWallpaperRequest(
                    "family", "桜", "https://example.com/sakura.jpg", null, null, null);

            // When
            ApiResponse<WallpaperResponse> result = service.createWallpaper(req);

            // Then
            assertThat(result.getData().getName()).isEqualTo("桜");
            verify(templateWallpaperRepository).save(any(TemplateWallpaperEntity.class));
        }
    }

    @Nested
    @DisplayName("deleteWallpaper")
    class DeleteWallpaper {

        @Test
        @DisplayName("異常系: 壁紙不在でFAMILY_020例外")
        void 削除_不在_例外() {
            // Given
            given(templateWallpaperRepository.findById(1L)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.deleteWallpaper(1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAMILY_020"));
        }
    }
}
