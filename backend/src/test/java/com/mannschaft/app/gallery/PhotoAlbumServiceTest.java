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
    /** CMP-028 Phase B: 可視レベル解決に用いる F00 メンバーシップ照会サービスのモック。 */
    @Mock
    private com.mannschaft.app.common.visibility.MembershipBatchQueryService membershipBatchQueryService;

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
    @DisplayName("listAlbums — CMP-028 Phase B: SQL述語化")
    class ListAlbums {

        /**
         * AC-5/AC-8: 可視レベル解決 → 逆写像 → SQL の visibility IN 述語、という新しい経路が
         * 呼ばれることを検証する。旧来の findByTeamIdOrderByEventDateDesc（無条件取得）が
         * 呼ばれなくなったことも合わせて確認する（メモリフィルタの完全撤去）。
         */
        @Test
        @DisplayName("正常系: resolveVisibleLevelsの結果をvisibility IN述語に渡してSQLで絞る")
        void 一覧_SQL述語で絞り込む() {
            try (MockedStatic<SecurityUtils> mock = Mockito.mockStatic(SecurityUtils.class)) {
                mock.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(USER_ID);

                com.mannschaft.app.common.visibility.ScopeKey scope =
                        new com.mannschaft.app.common.visibility.ScopeKey("TEAM", TEAM_ID);
                com.mannschaft.app.common.visibility.UserScopeRoleSnapshot snapshot =
                        com.mannschaft.app.common.visibility.UserScopeRoleSnapshot.empty();
                given(membershipBatchQueryService.snapshotForUser(
                        eq(USER_ID), eq(Set.of(scope)), eq(Set.of(scope))))
                        .willReturn(snapshot);
                given(membershipBatchQueryService.resolveVisibleLevels(eq(scope), eq(snapshot)))
                        .willReturn(Set.of(
                                com.mannschaft.app.common.visibility.StandardVisibility.PUBLIC,
                                com.mannschaft.app.common.visibility.StandardVisibility.SCOPE_AFFILIATED));

                PhotoAlbumEntity album1 = PhotoAlbumEntity.builder()
                        .teamId(TEAM_ID).title("アルバム1").visibility(AlbumVisibility.ALL_MEMBERS).build();
                ReflectionTestUtils.setField(album1, "id", 100L);
                Pageable pageable = PageRequest.of(0, 20);
                Page<PhotoAlbumEntity> page = new PageImpl<>(List.of(album1), pageable, 1);

                given(albumRepository.findByTeamIdAndVisibilityInOrderByEventDateDesc(
                        eq(TEAM_ID), eq(Set.of(AlbumVisibility.ALL_MEMBERS)), eq(pageable)))
                        .willReturn(page);
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
                Mockito.verify(albumRepository, Mockito.never())
                        .findByTeamIdOrderByEventDateDesc(any(), any());
            }
        }

        /**
         * AC-9: 可視レベルが PUBLIC のみ（非所属・未認証相当）の場合、AlbumVisibility には
         * PUBLIC 相当が無いため逆写像が空集合になり、SQL を発行せず空ページを返す
         * （IN () の不正 SQL を避けつつ fail-closed を維持する）。
         */
        @Test
        @DisplayName("AC-9: 可視レベルがPUBLICのみ（AlbumVisibilityに対応なし）ならSQLを発行せず空ページ")
        void 非所属は空ページ() {
            try (MockedStatic<SecurityUtils> mock = Mockito.mockStatic(SecurityUtils.class)) {
                mock.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(null);

                com.mannschaft.app.common.visibility.ScopeKey scope =
                        new com.mannschaft.app.common.visibility.ScopeKey("TEAM", TEAM_ID);
                com.mannschaft.app.common.visibility.UserScopeRoleSnapshot snapshot =
                        com.mannschaft.app.common.visibility.UserScopeRoleSnapshot.empty();
                given(membershipBatchQueryService.snapshotForUser(
                        eq(null), eq(Set.of(scope)), eq(Set.of(scope))))
                        .willReturn(snapshot);
                given(membershipBatchQueryService.resolveVisibleLevels(eq(scope), eq(snapshot)))
                        .willReturn(Set.of(com.mannschaft.app.common.visibility.StandardVisibility.PUBLIC));

                Pageable pageable = PageRequest.of(0, 20);
                Page<AlbumResponse> result = service.listAlbums(
                        TEAM_ID, null, null, null, null, null, pageable);

                assertThat(result.getContent()).isEmpty();
                Mockito.verifyNoInteractions(albumRepository, contentVisibilityChecker);
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
