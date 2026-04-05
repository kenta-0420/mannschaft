package com.mannschaft.app.admin;

import com.mannschaft.app.admin.dto.AnnouncementResponse;
import com.mannschaft.app.admin.dto.CreateAnnouncementRequest;
import com.mannschaft.app.admin.dto.UpdateAnnouncementRequest;
import com.mannschaft.app.admin.entity.PlatformAnnouncementEntity;
import com.mannschaft.app.admin.repository.PlatformAnnouncementRepository;
import com.mannschaft.app.admin.service.PlatformAnnouncementService;
import com.mannschaft.app.common.BusinessException;
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
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link PlatformAnnouncementService} の単体テスト。
 * お知らせのCRUD・公開操作を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformAnnouncementService 単体テスト")
class PlatformAnnouncementServiceTest {

    @Mock
    private PlatformAnnouncementRepository announcementRepository;

    @Mock
    private AnnouncementFeedbackMapper mapper;

    @InjectMocks
    private PlatformAnnouncementService service;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long ANNOUNCEMENT_ID = 1L;
    private static final Long USER_ID = 100L;

    private PlatformAnnouncementEntity createAnnouncementEntity() {
        return PlatformAnnouncementEntity.builder()
                .title("メンテナンスのお知らせ")
                .body("システムメンテナンスを実施します。")
                .priority("NORMAL")
                .targetScope("ALL")
                .isPinned(false)
                .createdBy(USER_ID)
                .build();
    }

    private PlatformAnnouncementEntity createPublishedAnnouncementEntity() {
        PlatformAnnouncementEntity entity = createAnnouncementEntity();
        entity.publish();
        return entity;
    }

    private AnnouncementResponse createAnnouncementResponse() {
        return new AnnouncementResponse(
                ANNOUNCEMENT_ID, "メンテナンスのお知らせ", "システムメンテナンスを実施します。",
                "NORMAL", "ALL", false, null, null, USER_ID, null, null);
    }

    // ========================================
    // getAllAnnouncements
    // ========================================

    @Nested
    @DisplayName("getAllAnnouncements")
    class GetAllAnnouncements {

        @Test
        @DisplayName("正常系: ページネーション付きでお知らせ一覧が返却される")
        void 取得_全件_ページ返却() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            PlatformAnnouncementEntity entity = createAnnouncementEntity();
            AnnouncementResponse response = createAnnouncementResponse();
            Page<PlatformAnnouncementEntity> page = new PageImpl<>(List.of(entity));

            given(announcementRepository.findAllByOrderByCreatedAtDesc(pageable)).willReturn(page);
            given(mapper.toAnnouncementResponse(entity)).willReturn(response);

            // When
            Page<AnnouncementResponse> result = service.getAllAnnouncements(pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getTitle()).isEqualTo("メンテナンスのお知らせ");
        }
    }

    // ========================================
    // getActiveAnnouncements
    // ========================================

    @Nested
    @DisplayName("getActiveAnnouncements")
    class GetActiveAnnouncements {

        @Test
        @DisplayName("正常系: 有効なお知らせ一覧が返却される")
        void 取得_有効_一覧返却() {
            // Given
            List<PlatformAnnouncementEntity> entities = List.of(createAnnouncementEntity());
            List<AnnouncementResponse> responses = List.of(createAnnouncementResponse());
            given(announcementRepository.findActiveAnnouncements(any(LocalDateTime.class))).willReturn(entities);
            given(mapper.toAnnouncementResponseList(entities)).willReturn(responses);

            // When
            List<AnnouncementResponse> result = service.getActiveAnnouncements();

            // Then
            assertThat(result).hasSize(1);
            verify(announcementRepository).findActiveAnnouncements(any(LocalDateTime.class));
        }
    }

    // ========================================
    // createAnnouncement
    // ========================================

    @Nested
    @DisplayName("createAnnouncement")
    class CreateAnnouncement {

        @Test
        @DisplayName("正常系: お知らせが作成される")
        void 作成_正常_お知らせ保存() {
            // Given
            CreateAnnouncementRequest req = new CreateAnnouncementRequest(
                    "テストお知らせ", "本文です", "HIGH", "TEAM", true,
                    LocalDateTime.of(2026, 12, 31, 23, 59));
            PlatformAnnouncementEntity savedEntity = createAnnouncementEntity();
            AnnouncementResponse response = createAnnouncementResponse();

            given(announcementRepository.save(any(PlatformAnnouncementEntity.class))).willReturn(savedEntity);
            given(mapper.toAnnouncementResponse(savedEntity)).willReturn(response);

            // When
            AnnouncementResponse result = service.createAnnouncement(req, USER_ID);

            // Then
            assertThat(result).isNotNull();
            verify(announcementRepository).save(any(PlatformAnnouncementEntity.class));
        }

        @Test
        @DisplayName("正常系: オプション項目がnullの場合デフォルト値がセットされる")
        void 作成_オプションNull_デフォルト値() {
            // Given
            CreateAnnouncementRequest req = new CreateAnnouncementRequest(
                    "テスト", "本文", null, null, null, null);
            PlatformAnnouncementEntity savedEntity = createAnnouncementEntity();
            AnnouncementResponse response = createAnnouncementResponse();

            given(announcementRepository.save(any(PlatformAnnouncementEntity.class))).willReturn(savedEntity);
            given(mapper.toAnnouncementResponse(savedEntity)).willReturn(response);

            // When
            service.createAnnouncement(req, USER_ID);

            // Then
            verify(announcementRepository).save(any(PlatformAnnouncementEntity.class));
        }
    }

    // ========================================
    // updateAnnouncement
    // ========================================

    @Nested
    @DisplayName("updateAnnouncement")
    class UpdateAnnouncement {

        @Test
        @DisplayName("正常系: お知らせが更新される")
        void 更新_正常_お知らせ保存() {
            // Given
            UpdateAnnouncementRequest req = new UpdateAnnouncementRequest(
                    "更新タイトル", "更新本文", "HIGH", "ORGANIZATION", true, null);
            PlatformAnnouncementEntity entity = createAnnouncementEntity();
            AnnouncementResponse response = createAnnouncementResponse();

            given(announcementRepository.findById(ANNOUNCEMENT_ID)).willReturn(Optional.of(entity));
            given(announcementRepository.save(entity)).willReturn(entity);
            given(mapper.toAnnouncementResponse(entity)).willReturn(response);

            // When
            AnnouncementResponse result = service.updateAnnouncement(ANNOUNCEMENT_ID, req);

            // Then
            assertThat(result).isNotNull();
            verify(announcementRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: お知らせ不在でADMIN_FB_001例外")
        void 更新_お知らせ不在_例外() {
            // Given
            UpdateAnnouncementRequest req = new UpdateAnnouncementRequest(
                    "タイトル", "本文", null, null, null, null);
            given(announcementRepository.findById(ANNOUNCEMENT_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.updateAnnouncement(ANNOUNCEMENT_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ADMIN_FB_001"));
        }
    }

    // ========================================
    // publishAnnouncement
    // ========================================

    @Nested
    @DisplayName("publishAnnouncement")
    class PublishAnnouncement {

        @Test
        @DisplayName("正常系: お知らせが公開される")
        void 公開_正常_publishedAt設定() {
            // Given
            PlatformAnnouncementEntity entity = createAnnouncementEntity();
            AnnouncementResponse response = createAnnouncementResponse();

            given(announcementRepository.findById(ANNOUNCEMENT_ID)).willReturn(Optional.of(entity));
            given(announcementRepository.save(entity)).willReturn(entity);
            given(mapper.toAnnouncementResponse(entity)).willReturn(response);

            // When
            AnnouncementResponse result = service.publishAnnouncement(ANNOUNCEMENT_ID);

            // Then
            assertThat(result).isNotNull();
            assertThat(entity.getPublishedAt()).isNotNull();
            verify(announcementRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: お知らせ不在でADMIN_FB_001例外")
        void 公開_お知らせ不在_例外() {
            // Given
            given(announcementRepository.findById(ANNOUNCEMENT_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.publishAnnouncement(ANNOUNCEMENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ADMIN_FB_001"));
        }

        @Test
        @DisplayName("異常系: 既に公開済みでADMIN_FB_002例外")
        void 公開_既に公開済み_例外() {
            // Given
            PlatformAnnouncementEntity entity = createPublishedAnnouncementEntity();
            given(announcementRepository.findById(ANNOUNCEMENT_ID)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.publishAnnouncement(ANNOUNCEMENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ADMIN_FB_002"));
        }
    }

    // ========================================
    // deleteAnnouncement
    // ========================================

    @Nested
    @DisplayName("deleteAnnouncement")
    class DeleteAnnouncement {

        @Test
        @DisplayName("正常系: お知らせが論理削除される")
        void 削除_正常_論理削除() {
            // Given
            PlatformAnnouncementEntity entity = createAnnouncementEntity();
            given(announcementRepository.findById(ANNOUNCEMENT_ID)).willReturn(Optional.of(entity));

            // When
            service.deleteAnnouncement(ANNOUNCEMENT_ID);

            // Then
            assertThat(entity.getDeletedAt()).isNotNull();
            verify(announcementRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: お知らせ不在でADMIN_FB_001例外")
        void 削除_お知らせ不在_例外() {
            // Given
            given(announcementRepository.findById(ANNOUNCEMENT_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.deleteAnnouncement(ANNOUNCEMENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ADMIN_FB_001"));
        }
    }
}
