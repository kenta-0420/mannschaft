package com.mannschaft.app.gallery;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.gallery.dto.AlbumResponse;
import com.mannschaft.app.gallery.dto.CreateAlbumRequest;
import com.mannschaft.app.gallery.entity.PhotoAlbumEntity;
import com.mannschaft.app.gallery.repository.PhotoAlbumRepository;
import com.mannschaft.app.gallery.service.PhotoAlbumService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import com.mannschaft.app.common.SecurityUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * {@link PhotoAlbumService} 単体テスト。
 *
 * <p>F00 Phase E-5: {@link ContentVisibilityChecker} のモックを追加し、
 * 可視性フィルタリングが正しく委譲されることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PhotoAlbumService 単体テスト")
class PhotoAlbumServiceTest {

    @Mock
    private PhotoAlbumRepository albumRepository;
    @Mock
    private GalleryMapper galleryMapper;
    /** F00 Phase E-5: ContentVisibilityChecker モック。 */
    @Mock
    private ContentVisibilityChecker contentVisibilityChecker;
    /** 認可根治戦役 Wave3-B5: 書込CRUD の scope 認可用モック。 */
    @Mock
    private AccessControlService accessControlService;

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
        @DisplayName("正常系: 閲覧可能アルバムが返される")
        void 取得_正常_可視性チェック通過() {
            try (MockedStatic<SecurityUtils> mock = Mockito.mockStatic(SecurityUtils.class)) {
                mock.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(USER_ID);
                doNothing().when(contentVisibilityChecker).assertCanView(
                        eq(ReferenceType.PHOTO_ALBUM), eq(ALBUM_ID), eq(USER_ID));
                PhotoAlbumEntity entity = PhotoAlbumEntity.builder()
                        .teamId(TEAM_ID).title("テストアルバム").build();
                given(albumRepository.findById(ALBUM_ID)).willReturn(Optional.of(entity));
                AlbumResponse expected = new AlbumResponse(
                        ALBUM_ID, TEAM_ID, null, "テストアルバム", null, null, null,
                        null, null, null, null, null, null, null);
                given(galleryMapper.toAlbumResponse(entity)).willReturn(expected);

                AlbumResponse result = service.getAlbum(ALBUM_ID);

                assertThat(result).isNotNull();
                assertThat(result.getTitle()).isEqualTo("テストアルバム");
                verify(contentVisibilityChecker).assertCanView(
                        ReferenceType.PHOTO_ALBUM, ALBUM_ID, USER_ID);
            }
        }

        @Test
        @DisplayName("異常系: 可視性チェック失敗で BusinessException")
        void 取得_可視性拒否_例外() {
            try (MockedStatic<SecurityUtils> mock = Mockito.mockStatic(SecurityUtils.class)) {
                mock.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(null);
                doThrow(new BusinessException(
                        com.mannschaft.app.common.visibility.VisibilityErrorCode.VISIBILITY_001))
                        .when(contentVisibilityChecker).assertCanView(
                                eq(ReferenceType.PHOTO_ALBUM), eq(ALBUM_ID), eq((Long) null));

                assertThatThrownBy(() -> service.getAlbum(ALBUM_ID))
                        .isInstanceOf(BusinessException.class);
            }
        }

        @Test
        @DisplayName("異常系: アルバム不在でGALLERY_001例外")
        void 取得_不在_例外() {
            try (MockedStatic<SecurityUtils> mock = Mockito.mockStatic(SecurityUtils.class)) {
                mock.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(USER_ID);
                doNothing().when(contentVisibilityChecker).assertCanView(
                        eq(ReferenceType.PHOTO_ALBUM), eq(ALBUM_ID), eq(USER_ID));
                given(albumRepository.findById(ALBUM_ID)).willReturn(Optional.empty());

                assertThatThrownBy(() -> service.getAlbum(ALBUM_ID))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                                .isEqualTo("GALLERY_001"));
            }
        }
    }

    @Nested
    @DisplayName("listAlbums — F00 Phase E-5 ContentVisibilityChecker 委譲")
    class ListAlbums {

        @Test
        @DisplayName("正常系: アクセス可能なアルバムのみ返される")
        void 一覧_正常_可視性フィルタ適用() {
            try (MockedStatic<SecurityUtils> mock = Mockito.mockStatic(SecurityUtils.class)) {
                mock.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(USER_ID);

                PhotoAlbumEntity album1 = PhotoAlbumEntity.builder()
                        .teamId(TEAM_ID).title("アルバム1").build();
                // ReflectionTestUtils で JPA 管理フィールド id を設定
                ReflectionTestUtils.setField(album1, "id", 100L);
                PhotoAlbumEntity album2 = PhotoAlbumEntity.builder()
                        .teamId(TEAM_ID).title("アルバム2").build();
                ReflectionTestUtils.setField(album2, "id", 200L);
                Pageable pageable = PageRequest.of(0, 20);
                Page<PhotoAlbumEntity> page = new PageImpl<>(List.of(album1, album2), pageable, 2);

                given(albumRepository.findByTeamIdOrderByEventDateDesc(TEAM_ID, pageable))
                        .willReturn(page);
                // album1 の ID のみアクセス可能（album2 は不可）
                given(contentVisibilityChecker.filterAccessible(
                        eq(ReferenceType.PHOTO_ALBUM), any(), eq(USER_ID)))
                        .willReturn(Set.of(100L));
                AlbumResponse response1 = new AlbumResponse(
                        100L, TEAM_ID, null, "アルバム1", null, null, null,
                        null, null, null, null, null, null, null);
                given(galleryMapper.toAlbumResponse(album1)).willReturn(response1);

                Page<AlbumResponse> result = service.listAlbums(
                        TEAM_ID, null, null, null, null, null, pageable);

                assertThat(result.getContent()).hasSize(1);
                assertThat(result.getContent().get(0).getTitle()).isEqualTo("アルバム1");
                verify(contentVisibilityChecker).filterAccessible(
                        eq(ReferenceType.PHOTO_ALBUM), any(), eq(USER_ID));
            }
        }

        @Test
        @DisplayName("回帰: 総件数はDBの総件数からF00で落ちた件数を差し引いた値になる（PageImpl総件数バグ是正）")
        void 一覧_総件数はDB総件数ベース_絞り込み後件数ではない() {
            try (MockedStatic<SecurityUtils> mock = Mockito.mockStatic(SecurityUtils.class)) {
                mock.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(USER_ID);

                // ページサイズ2、DB全体では5件（=総件数5）のうち今回のページに2件取得。
                // うち1件（album2）はF00で非公開と判定され除外される想定。
                PhotoAlbumEntity album1 = PhotoAlbumEntity.builder()
                        .teamId(TEAM_ID).title("アルバム1（公開）").build();
                ReflectionTestUtils.setField(album1, "id", 100L);
                PhotoAlbumEntity album2 = PhotoAlbumEntity.builder()
                        .teamId(TEAM_ID).title("アルバム2（非公開・除外対象）").build();
                ReflectionTestUtils.setField(album2, "id", 200L);
                Pageable pageable = PageRequest.of(0, 2);
                // DB側の総件数は5（絞り込み前）。
                Page<PhotoAlbumEntity> page = new PageImpl<>(List.of(album1, album2), pageable, 5);

                given(albumRepository.findByTeamIdOrderByEventDateDesc(TEAM_ID, pageable))
                        .willReturn(page);
                // album1 のみアクセス可能（album2 は除外＝片方だけが残ることを検証）
                given(contentVisibilityChecker.filterAccessible(
                        eq(ReferenceType.PHOTO_ALBUM), any(), eq(USER_ID)))
                        .willReturn(Set.of(100L));
                AlbumResponse response1 = new AlbumResponse(
                        100L, TEAM_ID, null, "アルバム1（公開）", null, null, null,
                        null, null, null, null, null, null, null);
                given(galleryMapper.toAlbumResponse(album1)).willReturn(response1);

                Page<AlbumResponse> result = service.listAlbums(
                        TEAM_ID, null, null, null, null, null, pageable);

                // 旧実装のバグでは filtered.size()=1 が総件数になっていた。
                // 是正後は「DB総件数5 − このページで落ちた件数1」= 4 になるはず。
                assertThat(result.getTotalElements()).isEqualTo(4L);
                assertThat(result.getContent()).hasSize(1);
                assertThat(result.getContent().get(0).getTitle()).isEqualTo("アルバム1（公開）");
            }
        }

        @Test
        @DisplayName("正常系: アルバムが空の場合は ContentVisibilityChecker を呼ばない")
        void 一覧_空ページ_フィルタ不要() {
            try (MockedStatic<SecurityUtils> mock = Mockito.mockStatic(SecurityUtils.class)) {
                mock.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(USER_ID);
                Pageable pageable = PageRequest.of(0, 20);
                Page<PhotoAlbumEntity> emptyPage = new PageImpl<>(List.of(), pageable, 0);

                given(albumRepository.findByTeamIdOrderByEventDateDesc(TEAM_ID, pageable))
                        .willReturn(emptyPage);

                Page<AlbumResponse> result = service.listAlbums(
                        TEAM_ID, null, null, null, null, null, pageable);

                assertThat(result.getContent()).isEmpty();
                Mockito.verifyNoInteractions(contentVisibilityChecker);
            }
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
            service.deleteAlbum(ALBUM_ID, USER_ID);
            verify(albumRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: ADMIN権限なしはBusinessException（認可根治戦役 Wave3-B5）")
        void 削除_非ADMIN_例外() {
            PhotoAlbumEntity entity = PhotoAlbumEntity.builder().teamId(TEAM_ID).title("削除用").build();
            given(albumRepository.findById(ALBUM_ID)).willReturn(Optional.of(entity));
            doThrow(new BusinessException(com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> service.deleteAlbum(ALBUM_ID, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
