package com.mannschaft.app.gallery;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.gallery.dto.AlbumResponse;
import com.mannschaft.app.gallery.dto.CreateAlbumRequest;
import com.mannschaft.app.gallery.dto.UpdateAlbumRequest;
import com.mannschaft.app.gallery.entity.PhotoAlbumEntity;
import com.mannschaft.app.gallery.repository.PhotoAlbumRepository;
import com.mannschaft.app.gallery.service.PhotoAlbumService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("PhotoAlbumService 単体テスト")
class PhotoAlbumServiceTest {

    @Mock
    private PhotoAlbumRepository albumRepository;
    @Mock
    private GalleryMapper galleryMapper;

    @InjectMocks
    private PhotoAlbumService service;

    private static final Long TEAM_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long ALBUM_ID = 10L;

    @Nested
    @DisplayName("createAlbum")
    class CreateAlbum {
        @Test
        @DisplayName("正常系: アルバムが作成される")
        void 作成_正常_保存() {
            CreateAlbumRequest request = new CreateAlbumRequest(
                    TEAM_ID, null, "テストアルバム", null, LocalDate.now(), null, null, null);
            PhotoAlbumEntity saved = PhotoAlbumEntity.builder().teamId(TEAM_ID).title("テストアルバム").build();
            given(albumRepository.save(any())).willReturn(saved);
            given(galleryMapper.toAlbumResponse(saved)).willReturn(new AlbumResponse(
                    null, TEAM_ID, null, "テストアルバム", null, null, LocalDate.now(),
                    null, null, null, null, null, null, null));

            AlbumResponse result = service.createAlbum(USER_ID, request);
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("getAlbum")
    class GetAlbum {
        @Test
        @DisplayName("異常系: アルバム不在でGALLERY_001例外")
        void 取得_不在_例外() {
            given(albumRepository.findById(ALBUM_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.getAlbum(ALBUM_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("GALLERY_001"));
        }
    }

    @Nested
    @DisplayName("deleteAlbum")
    class DeleteAlbum {
        @Test
        @DisplayName("正常系: アルバムが論理削除される")
        void 削除_正常_論理削除() {
            PhotoAlbumEntity entity = PhotoAlbumEntity.builder().teamId(TEAM_ID).title("削除用").build();
            given(albumRepository.findById(ALBUM_ID)).willReturn(Optional.of(entity));
            service.deleteAlbum(ALBUM_ID);
            verify(albumRepository).save(entity);
        }
    }
}
