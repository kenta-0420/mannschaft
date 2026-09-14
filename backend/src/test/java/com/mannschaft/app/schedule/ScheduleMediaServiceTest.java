package com.mannschaft.app.schedule;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.quota.StorageFeatureType;
import com.mannschaft.app.common.storage.quota.StorageQuotaExceededException;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import com.mannschaft.app.files.dto.StartMultipartUploadRequest;
import com.mannschaft.app.files.dto.StartMultipartUploadResponse;
import com.mannschaft.app.files.service.MultipartUploadService;
import com.mannschaft.app.schedule.dto.ScheduleMediaListResponse;
import com.mannschaft.app.schedule.dto.ScheduleMediaPatchRequest;
import com.mannschaft.app.schedule.dto.ScheduleMediaResponse;
import com.mannschaft.app.schedule.dto.ScheduleMediaUploadUrlRequest;
import com.mannschaft.app.schedule.dto.ScheduleMediaUploadUrlResponse;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleMediaUploadEntity;
import com.mannschaft.app.schedule.repository.ScheduleMediaUploadRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.ScheduleMediaQueryService;
import com.mannschaft.app.schedule.service.ScheduleMediaService;
import com.mannschaft.app.schedule.service.ScheduleMediaUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * {@link ScheduleMediaService} の単体テスト。
 * Mockito で R2StorageService / MultipartUploadService / Repository をモックして
 * ビジネスロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleMediaService 単体テスト")
class ScheduleMediaServiceTest {

    @Mock
    private R2StorageService r2StorageService;

    @Mock
    private MultipartUploadService multipartUploadService;

    @Mock
    private ScheduleMediaUploadRepository scheduleMediaUploadRepository;

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private StorageQuotaService storageQuotaService;

    @Mock
    private AccessControlService accessControlService;

    /**
     * リファクタリング第6弾（2026-05-17）以降、{@link ScheduleMediaService} は
     * {@link ScheduleMediaUploadService} と {@link ScheduleMediaQueryService} へ
     * 委譲する薄いファサードになった。テストではモック群から各サービスを手動で組み立てる。
     */
    private ScheduleMediaService scheduleMediaService;

    @BeforeEach
    void setUp() {
        ScheduleMediaUploadService uploadService = new ScheduleMediaUploadService(
                r2StorageService,
                multipartUploadService,
                scheduleMediaUploadRepository,
                scheduleRepository,
                storageQuotaService);
        ScheduleMediaQueryService queryService = new ScheduleMediaQueryService(
                r2StorageService,
                scheduleMediaUploadRepository,
                scheduleRepository,
                accessControlService,
                storageQuotaService);
        scheduleMediaService = new ScheduleMediaService(uploadService, queryService);

        ScheduleEntity defaultSchedule = ScheduleEntity.builder()
                .id(SCHEDULE_ID)
                .teamId(10L)
                .build();
        lenient().when(scheduleRepository.findById(SCHEDULE_ID))
                .thenReturn(Optional.of(defaultSchedule));
        lenient().when(accessControlService.isMember(UPLOADER_ID, 10L, "TEAM"))
                .thenReturn(true);
    }

    /** テスト用固定ユーザー ID */
    private static final Long UPLOADER_ID = 1L;

    /** テスト用スケジュール ID */
    private static final Long SCHEDULE_ID = 100L;

    /** テスト用メディア ID */
    private static final Long MEDIA_ID = 200L;

    /** 別ユーザー ID（権限テスト用） */
    private static final Long OTHER_USER_ID = 2L;

    /**
     * ScheduleMediaUploadUrlRequest をリフレクションで組み立てるヘルパー。
     */
    private ScheduleMediaUploadUrlRequest buildRequest(
            String mediaType, String contentType, long fileSize, String fileName) {
        ScheduleMediaUploadUrlRequest req = new ScheduleMediaUploadUrlRequest();
        ReflectionTestUtils.setField(req, "mediaType", mediaType);
        ReflectionTestUtils.setField(req, "contentType", contentType);
        ReflectionTestUtils.setField(req, "fileSize", fileSize);
        ReflectionTestUtils.setField(req, "fileName", fileName);
        return req;
    }

    /**
     * ScheduleMediaPatchRequest をリフレクションで組み立てるヘルパー。
     */
    private ScheduleMediaPatchRequest buildPatchRequest(
            String caption, LocalDateTime takenAt, Boolean isCover, Boolean isExpenseReceipt) {
        ScheduleMediaPatchRequest req = new ScheduleMediaPatchRequest();
        ReflectionTestUtils.setField(req, "caption", caption);
        ReflectionTestUtils.setField(req, "takenAt", takenAt);
        ReflectionTestUtils.setField(req, "isCover", isCover);
        ReflectionTestUtils.setField(req, "isExpenseReceipt", isExpenseReceipt);
        return req;
    }

    /**
     * テスト用メディアエンティティを組み立てるヘルパー。
     */
    private ScheduleMediaUploadEntity buildMediaEntity(
            Long id, Long scheduleId, Long uploaderId, String mediaType) {
        return ScheduleMediaUploadEntity.builder()
                .id(id)
                .scheduleId(scheduleId)
                .uploaderId(uploaderId)
                .mediaType(mediaType)
                .r2Key("schedules/" + scheduleId + "/uuid.jpg")
                .fileName("photo.jpg")
                .fileSize(1024L * 1024)
                .contentType("image/jpeg")
                .processingStatus("READY")
                .build();
    }

    /**
     * スケジュール存在確認用のモック ScheduleEntity を返すヘルパー。
     * given() の外で mock() を呼んで UnfinishedStubbingException を防ぐ。
     */
    private ScheduleEntity mockScheduleEntity() {
        return mock(ScheduleEntity.class);
    }

    private ScheduleEntity mockTeamSchedule(Long teamId) {
        return ScheduleEntity.builder().id(SCHEDULE_ID).teamId(teamId).build();
    }

    private ScheduleEntity mockOrganizationSchedule(Long organizationId) {
        return ScheduleEntity.builder().id(SCHEDULE_ID).organizationId(organizationId).build();
    }

    private ScheduleEntity mockPersonalSchedule(Long ownerId) {
        return ScheduleEntity.builder().id(SCHEDULE_ID).userId(ownerId).build();
    }

    private void givenSchedule(ScheduleEntity schedule) {
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));
    }

    // ==================== generateUploadUrl ====================

    @Nested
    @DisplayName("generateUploadUrl")
    class GenerateUploadUrl {

        @Test
        @DisplayName("正常系_IMAGE_Presigned URL 発行成功")
        void 正常系_IMAGE_PresignedURL発行成功() {
            // given
            ScheduleEntity dummySchedule = mockScheduleEntity();
            ScheduleMediaUploadUrlRequest req = buildRequest("IMAGE", "image/jpeg", 1024L * 1024, "photo.jpg");
            given(scheduleRepository.findById(SCHEDULE_ID))
                    .willReturn(Optional.of(dummySchedule));
            given(scheduleMediaUploadRepository.countByScheduleIdAndMediaType(SCHEDULE_ID, "IMAGE"))
                    .willReturn(0);
            given(r2StorageService.generateUploadUrl(anyString(), eq("image/jpeg"), any()))
                    .willReturn(new PresignedUploadResult(
                            "https://r2.example.com/presigned-put-url",
                            "schedules/100/uuid.jpg",
                            600L));
            given(scheduleMediaUploadRepository.save(any(ScheduleMediaUploadEntity.class)))
                    .willAnswer(inv -> {
                        ScheduleMediaUploadEntity entity = inv.getArgument(0);
                        return ScheduleMediaUploadEntity.builder()
                                .id(MEDIA_ID)
                                .scheduleId(entity.getScheduleId())
                                .uploaderId(entity.getUploaderId())
                                .mediaType(entity.getMediaType())
                                .r2Key(entity.getR2Key())
                                .fileName(entity.getFileName())
                                .fileSize(entity.getFileSize())
                                .contentType(entity.getContentType())
                                .processingStatus(entity.getProcessingStatus())
                                .build();
                    });

            // when
            ScheduleMediaUploadUrlResponse result =
                    scheduleMediaService.generateUploadUrl(SCHEDULE_ID, UPLOADER_ID, req);

            // then
            assertThat(result.getMediaType()).isEqualTo("IMAGE");
            assertThat(result.getUploadUrl()).isEqualTo("https://r2.example.com/presigned-put-url");
            assertThat(result.getExpiresIn()).isEqualTo(600);
            // F13 Phase 5-a: 新統一パス "schedules/{scopeType}/{scopeId}/{scheduleId}/" を検証
            // dummySchedule は mock() → getTeamId() が null でない場合 TEAM スコープ
            // スケジュールID=100 を含むことを確認する
            assertThat(result.getR2Key()).contains("/100/");
            assertThat(result.getR2Key()).startsWith("schedules/");
            assertThat(result.getUploadId()).isNull();
            assertThat(result.getPartSize()).isNull();
            then(r2StorageService).should()
                    .generateUploadUrl(anyString(), eq("image/jpeg"), any());
            then(scheduleMediaUploadRepository).should().save(any(ScheduleMediaUploadEntity.class));
        }

        @Test
        @DisplayName("正常系_VIDEO_Multipart Upload 開始成功")
        void 正常系_VIDEO_MultipartUpload開始成功() {
            // given
            ScheduleEntity dummySchedule = mockScheduleEntity();
            ScheduleMediaUploadUrlRequest req = buildRequest(
                    "VIDEO", "video/mp4", 200L * 1024 * 1024, "movie.mp4");
            given(scheduleRepository.findById(SCHEDULE_ID))
                    .willReturn(Optional.of(dummySchedule));
            given(scheduleMediaUploadRepository.countByScheduleIdAndMediaType(SCHEDULE_ID, "VIDEO"))
                    .willReturn(0);
            given(multipartUploadService.startUpload(eq(UPLOADER_ID), any(StartMultipartUploadRequest.class)))
                    .willReturn(new StartMultipartUploadResponse(
                            "test-upload-id",
                            "schedules/100/uuid.mp4",
                            1,
                            10L * 1024 * 1024));
            given(scheduleMediaUploadRepository.save(any(ScheduleMediaUploadEntity.class)))
                    .willAnswer(inv -> {
                        ScheduleMediaUploadEntity entity = inv.getArgument(0);
                        return ScheduleMediaUploadEntity.builder()
                                .id(MEDIA_ID)
                                .scheduleId(entity.getScheduleId())
                                .uploaderId(entity.getUploaderId())
                                .mediaType(entity.getMediaType())
                                .r2Key(entity.getR2Key())
                                .fileName(entity.getFileName())
                                .fileSize(entity.getFileSize())
                                .contentType(entity.getContentType())
                                .processingStatus(entity.getProcessingStatus())
                                .build();
                    });

            // when
            ScheduleMediaUploadUrlResponse result =
                    scheduleMediaService.generateUploadUrl(SCHEDULE_ID, UPLOADER_ID, req);

            // then
            assertThat(result.getMediaType()).isEqualTo("VIDEO");
            assertThat(result.getUploadId()).isEqualTo("test-upload-id");
            assertThat(result.getPartSize()).isEqualTo(10L * 1024 * 1024);
            assertThat(result.getR2Key()).isEqualTo("schedules/100/uuid.mp4");
            assertThat(result.getUploadUrl()).isNull();
            assertThat(result.getExpiresIn()).isNull();
            then(multipartUploadService).should()
                    .startUpload(eq(UPLOADER_ID), any(StartMultipartUploadRequest.class));
        }

        @Test
        @DisplayName("異常系_スケジュール存在しない_404")
        void 異常系_スケジュール存在しない_404() {
            // given
            ScheduleMediaUploadUrlRequest req = buildRequest("IMAGE", "image/jpeg", 1024L, "photo.jpg");
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() ->
                    scheduleMediaService.generateUploadUrl(SCHEDULE_ID, UPLOADER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
            then(r2StorageService).should(never()).generateUploadUrl(anyString(), anyString(), any());
            then(scheduleMediaUploadRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("異常系_MIME タイプ不正_400")
        void 異常系_MIMEタイプ不正_400() {
            // given: 非許可の画像形式（image/bmp）
            ScheduleEntity dummySchedule = mockScheduleEntity();
            ScheduleMediaUploadUrlRequest req = buildRequest("IMAGE", "image/bmp", 1024L, "photo.bmp");
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(dummySchedule));

            // when / then
            assertThatThrownBy(() ->
                    scheduleMediaService.generateUploadUrl(SCHEDULE_ID, UPLOADER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST));
            then(r2StorageService).should(never()).generateUploadUrl(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("異常系_IMAGE サイズ超過（50MB超）_400")
        void 異常系_IMAGEサイズ超過_400() {
            // given: 50MB + 1バイト
            ScheduleEntity dummySchedule = mockScheduleEntity();
            long oversized = 50L * 1024 * 1024 + 1;
            ScheduleMediaUploadUrlRequest req = buildRequest("IMAGE", "image/jpeg", oversized, "photo.jpg");
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(dummySchedule));

            // when / then
            assertThatThrownBy(() ->
                    scheduleMediaService.generateUploadUrl(SCHEDULE_ID, UPLOADER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST));
        }

        @Test
        @DisplayName("異常系_画像上限超過（50枚）_422")
        void 異常系_画像上限超過_422() {
            // given: 既に 50 枚アップロード済み
            ScheduleEntity dummySchedule = mockScheduleEntity();
            ScheduleMediaUploadUrlRequest req = buildRequest("IMAGE", "image/jpeg", 1024L, "photo.jpg");
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(dummySchedule));
            given(scheduleMediaUploadRepository.countByScheduleIdAndMediaType(SCHEDULE_ID, "IMAGE"))
                    .willReturn(50);

            // when / then
            assertThatThrownBy(() ->
                    scheduleMediaService.generateUploadUrl(SCHEDULE_ID, UPLOADER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        }

        @Test
        @DisplayName("異常系_動画本数上限超過（5本）_422")
        void 異常系_動画本数上限超過_422() {
            // given: 既に 5 本アップロード済み
            ScheduleEntity dummySchedule = mockScheduleEntity();
            ScheduleMediaUploadUrlRequest req = buildRequest("VIDEO", "video/mp4", 10L * 1024 * 1024, "movie.mp4");
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(dummySchedule));
            given(scheduleMediaUploadRepository.countByScheduleIdAndMediaType(SCHEDULE_ID, "VIDEO"))
                    .willReturn(5);

            // when / then
            assertThatThrownBy(() ->
                    scheduleMediaService.generateUploadUrl(SCHEDULE_ID, UPLOADER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        }
    }

    // ==================== listMedia ====================

    @Nested
    @DisplayName("listMedia")
    class ListMedia {

        @Test
        @DisplayName("正常系_全件取得")
        void 正常系_全件取得() {
            // given
            ScheduleEntity dummySchedule = mockScheduleEntity();
            ScheduleMediaUploadEntity entity = buildMediaEntity(MEDIA_ID, SCHEDULE_ID, UPLOADER_ID, "IMAGE");
            Page<ScheduleMediaUploadEntity> page = new PageImpl<>(List.of(entity));
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(dummySchedule));
            given(scheduleMediaUploadRepository.findByScheduleIdOrderByCreatedAtDesc(
                    eq(SCHEDULE_ID), any(Pageable.class))).willReturn(page);

            // when
            ScheduleMediaListResponse result =
                    scheduleMediaService.listMedia(SCHEDULE_ID, null, false, 1, 20);

            // then
            assertThat(result.getItems()).hasSize(1);
            assertThat(result.getTotalCount()).isEqualTo(1);
            assertThat(result.getPage()).isEqualTo(1);
            assertThat(result.getSize()).isEqualTo(20);
            then(scheduleMediaUploadRepository).should()
                    .findByScheduleIdOrderByCreatedAtDesc(eq(SCHEDULE_ID), any(Pageable.class));
        }

        @Test
        @DisplayName("正常系_mediaType フィルタ適用")
        void 正常系_mediaTypeフィルタ() {
            // given
            ScheduleEntity dummySchedule = mockScheduleEntity();
            ScheduleMediaUploadEntity entity = buildMediaEntity(MEDIA_ID, SCHEDULE_ID, UPLOADER_ID, "IMAGE");
            Page<ScheduleMediaUploadEntity> page = new PageImpl<>(List.of(entity));
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(dummySchedule));
            given(scheduleMediaUploadRepository.findByScheduleIdAndMediaTypeOrderByCreatedAtDesc(
                    eq(SCHEDULE_ID), eq("IMAGE"), any(Pageable.class))).willReturn(page);

            // when
            ScheduleMediaListResponse result =
                    scheduleMediaService.listMedia(SCHEDULE_ID, "IMAGE", false, 1, 20);

            // then
            assertThat(result.getItems()).hasSize(1);
            then(scheduleMediaUploadRepository).should()
                    .findByScheduleIdAndMediaTypeOrderByCreatedAtDesc(
                            eq(SCHEDULE_ID), eq("IMAGE"), any(Pageable.class));
        }

        @Test
        @DisplayName("正常系_expenseReceiptOnly フィルタ適用")
        void 正常系_expenseReceiptOnlyフィルタ() {
            // given
            ScheduleEntity dummySchedule = mockScheduleEntity();
            ScheduleMediaUploadEntity entity = ScheduleMediaUploadEntity.builder()
                    .id(MEDIA_ID)
                    .scheduleId(SCHEDULE_ID)
                    .uploaderId(UPLOADER_ID)
                    .mediaType("IMAGE")
                    .r2Key("schedules/100/receipt.jpg")
                    .fileName("receipt.jpg")
                    .fileSize(512L * 1024)
                    .contentType("image/jpeg")
                    .processingStatus("READY")
                    .isExpenseReceipt(true)
                    .build();
            Page<ScheduleMediaUploadEntity> page = new PageImpl<>(List.of(entity));
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(dummySchedule));
            given(scheduleMediaUploadRepository.findByScheduleIdAndIsExpenseReceiptTrueOrderByCreatedAtDesc(
                    eq(SCHEDULE_ID), any(Pageable.class))).willReturn(page);

            // when
            ScheduleMediaListResponse result =
                    scheduleMediaService.listMedia(SCHEDULE_ID, null, true, 1, 20);

            // then
            assertThat(result.getItems()).hasSize(1);
            then(scheduleMediaUploadRepository).should()
                    .findByScheduleIdAndIsExpenseReceiptTrueOrderByCreatedAtDesc(
                            eq(SCHEDULE_ID), any(Pageable.class));
        }

        @Test
        @DisplayName("異常系_スケジュール存在しない_404")
        void 異常系_スケジュール存在しない_404() {
            // given
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() ->
                    scheduleMediaService.listMedia(SCHEDULE_ID, null, false, 1, 20))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    // ==================== updateMedia ====================

    @Nested
    @DisplayName("updateMedia")
    class UpdateMedia {

        @Test
        @DisplayName("正常系_キャプション更新")
        void 正常系_キャプション更新() {
            // given
            ScheduleMediaUploadEntity entity = buildMediaEntity(MEDIA_ID, SCHEDULE_ID, UPLOADER_ID, "IMAGE");
            ScheduleMediaPatchRequest req = buildPatchRequest("新しいキャプション", null, null, null);
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));
            givenSchedule(mockPersonalSchedule(UPLOADER_ID));
            given(scheduleMediaUploadRepository.save(any(ScheduleMediaUploadEntity.class)))
                    .willReturn(entity);

            // when
            ScheduleMediaResponse result =
                    scheduleMediaService.updateMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID, req);

            // then
            assertThat(result).isNotNull();
            then(scheduleMediaUploadRepository).should().save(entity);
        }

        @Test
        @DisplayName("正常系_isCover=true に更新（ADMIN） → カバー切り替え")
        void 正常系_isCover更新_ADMIN_カバー切り替え() {
            // given
            ScheduleMediaUploadEntity entity = buildMediaEntity(MEDIA_ID, SCHEDULE_ID, UPLOADER_ID, "IMAGE");
            ScheduleMediaUploadEntity existingCover = ScheduleMediaUploadEntity.builder()
                    .id(999L)
                    .scheduleId(SCHEDULE_ID)
                    .uploaderId(UPLOADER_ID)
                    .mediaType("IMAGE")
                    .r2Key("schedules/100/cover.jpg")
                    .fileName("cover.jpg")
                    .fileSize(1024L)
                    .contentType("image/jpeg")
                    .processingStatus("READY")
                    .isCover(true)
                    .build();
            ScheduleMediaPatchRequest req = buildPatchRequest(null, null, true, null);
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));
            givenSchedule(mockTeamSchedule(10L));
            given(accessControlService.isAdmin(UPLOADER_ID, 10L, "TEAM")).willReturn(true);
            given(scheduleMediaUploadRepository.findByScheduleIdAndIsCoverTrue(SCHEDULE_ID))
                    .willReturn(List.of(existingCover));
            given(scheduleMediaUploadRepository.save(any(ScheduleMediaUploadEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // when
            scheduleMediaService.updateMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID, req);

            // then: 既存カバーと新カバーの両方に save が呼ばれる
            then(scheduleMediaUploadRepository).should(org.mockito.Mockito.atLeast(2))
                    .save(any(ScheduleMediaUploadEntity.class));
        }

        @Test
        @DisplayName("異常系_isCover 変更を MEMBER が試みる_403")
        void 異常系_isCover変更をMEMBERが試みる_403() {
            // given
            ScheduleMediaUploadEntity entity = buildMediaEntity(MEDIA_ID, SCHEDULE_ID, UPLOADER_ID, "IMAGE");
            ScheduleMediaPatchRequest req = buildPatchRequest(null, null, true, null);
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));
            givenSchedule(mockTeamSchedule(10L));

            // when / then
            assertThatThrownBy(() ->
                    scheduleMediaService.updateMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }

        @Test
        @DisplayName("異常系_他人のメディアを MEMBER が更新_403")
        void 異常系_他人のメディアをMEMBERが更新_403() {
            // given: entity の uploaderId は OTHER_USER_ID
            ScheduleMediaUploadEntity entity = buildMediaEntity(MEDIA_ID, SCHEDULE_ID, OTHER_USER_ID, "IMAGE");
            ScheduleMediaPatchRequest req = buildPatchRequest("キャプション変更", null, null, null);
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));
            givenSchedule(mockTeamSchedule(10L));

            // when / then
            assertThatThrownBy(() ->
                    scheduleMediaService.updateMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }

        @Test
        @DisplayName("異常系_isExpenseReceipt=false 変更を MEMBER が試みる_403")
        void 異常系_isExpenseReceipt変更をMEMBERが試みる_403() {
            // given
            ScheduleMediaUploadEntity entity = buildMediaEntity(MEDIA_ID, SCHEDULE_ID, UPLOADER_ID, "IMAGE");
            ScheduleMediaPatchRequest req = buildPatchRequest(null, null, null, false);
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));
            givenSchedule(mockTeamSchedule(10L));

            // when / then
            assertThatThrownBy(() ->
                    scheduleMediaService.updateMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }

        @Test
        @DisplayName("異常系_メディアが存在しない_404")
        void 異常系_メディアが存在しない_404() {
            // given
            ScheduleMediaPatchRequest req = buildPatchRequest("キャプション", null, null, null);
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() ->
                    scheduleMediaService.updateMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("異常系_scheduleId 不一致_404")
        void 異常系_scheduleId不一致_404() {
            // given: entity は別の scheduleId に属する
            ScheduleMediaUploadEntity entity = buildMediaEntity(MEDIA_ID, 999L, UPLOADER_ID, "IMAGE");
            ScheduleMediaPatchRequest req = buildPatchRequest("キャプション", null, null, null);
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));

            // when / then
            assertThatThrownBy(() ->
                    scheduleMediaService.updateMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("正常系_TEAMの権限付きDEPUTY_ADMINが他人のメディアを更新")
        void 正常系_TEAM権限付き副管理者が他人のメディアを更新() {
            ScheduleMediaUploadEntity entity =
                    buildMediaEntity(MEDIA_ID, SCHEDULE_ID, OTHER_USER_ID, "IMAGE");
            ScheduleMediaPatchRequest req = buildPatchRequest("管理更新", null, null, null);
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));
            givenSchedule(mockTeamSchedule(10L));
            given(accessControlService.getRoleName(UPLOADER_ID, 10L, "TEAM"))
                    .willReturn("DEPUTY_ADMIN");
            given(accessControlService.hasPermission(
                    UPLOADER_ID, 10L, "TEAM", "MANAGE_SCHEDULES")).willReturn(true);
            given(scheduleMediaUploadRepository.save(entity)).willReturn(entity);

            ScheduleMediaResponse result =
                    scheduleMediaService.updateMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID, req);

            assertThat(result).isNotNull();
            then(scheduleMediaUploadRepository).should().save(entity);
        }

        @Test
        @DisplayName("異常系_MANAGE_SCHEDULESを持つMEMBERは管理者扱いしない")
        void 異常系_権限付き一般会員は他人のメディアを更新不可() {
            ScheduleMediaUploadEntity entity =
                    buildMediaEntity(MEDIA_ID, SCHEDULE_ID, OTHER_USER_ID, "IMAGE");
            ScheduleMediaPatchRequest req = buildPatchRequest("越権更新", null, null, null);
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));
            givenSchedule(mockTeamSchedule(10L));
            given(accessControlService.getRoleName(UPLOADER_ID, 10L, "TEAM"))
                    .willReturn("MEMBER");

            assertThatThrownBy(() ->
                    scheduleMediaService.updateMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN));
            then(accessControlService).should(never()).hasPermission(
                    UPLOADER_ID, 10L, "TEAM", "MANAGE_SCHEDULES");
            then(scheduleMediaUploadRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("正常系_ORGANIZATIONのADMINが他人のメディアを更新")
        void 正常系_ORGANIZATION管理者が他人のメディアを更新() {
            ScheduleMediaUploadEntity entity =
                    buildMediaEntity(MEDIA_ID, SCHEDULE_ID, OTHER_USER_ID, "IMAGE");
            ScheduleMediaPatchRequest req = buildPatchRequest("組織管理更新", null, null, null);
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));
            givenSchedule(mockOrganizationSchedule(20L));
            given(accessControlService.isAdmin(UPLOADER_ID, 20L, "ORGANIZATION")).willReturn(true);
            given(scheduleMediaUploadRepository.save(entity)).willReturn(entity);

            scheduleMediaService.updateMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID, req);

            then(scheduleMediaUploadRepository).should().save(entity);
        }

        @Test
        @DisplayName("異常系_SYSTEM_ADMINはメディア更新権限を持たない")
        void 異常系_SYSTEM_ADMINは更新不可() {
            ScheduleMediaUploadEntity entity =
                    buildMediaEntity(MEDIA_ID, SCHEDULE_ID, OTHER_USER_ID, "IMAGE");
            ScheduleMediaPatchRequest req = buildPatchRequest("強制更新", null, null, null);
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));
            givenSchedule(mockTeamSchedule(10L));
            given(accessControlService.getRoleName(UPLOADER_ID, 10L, "TEAM"))
                    .willReturn("SYSTEM_ADMIN");

            assertThatThrownBy(() ->
                    scheduleMediaService.updateMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN));
            then(accessControlService).should(never()).isSystemAdmin(any());
        }

        @Test
        @DisplayName("異常系_PERSONAL予定に他スコープの管理権限を持ち込まない")
        void 異常系_PERSONAL予定の他人メディアは更新不可() {
            ScheduleMediaUploadEntity entity =
                    buildMediaEntity(MEDIA_ID, SCHEDULE_ID, OTHER_USER_ID, "IMAGE");
            ScheduleMediaPatchRequest req = buildPatchRequest("越権更新", null, null, null);
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));
            givenSchedule(mockPersonalSchedule(OTHER_USER_ID));

            assertThatThrownBy(() ->
                    scheduleMediaService.updateMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN));
            then(accessControlService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("異常系_DEPUTY_ADMINでもMANAGE_SCHEDULESがなければ更新不可")
        void 異常系_権限なし副管理者は他人のメディアを更新不可() {
            ScheduleMediaUploadEntity entity =
                    buildMediaEntity(MEDIA_ID, SCHEDULE_ID, OTHER_USER_ID, "IMAGE");
            ScheduleMediaPatchRequest req = buildPatchRequest("越権更新", null, null, null);
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));
            givenSchedule(mockTeamSchedule(10L));
            given(accessControlService.getRoleName(UPLOADER_ID, 10L, "TEAM"))
                    .willReturn("DEPUTY_ADMIN");

            assertThatThrownBy(() ->
                    scheduleMediaService.updateMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN));
            then(scheduleMediaUploadRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("異常系_PERSONAL予定ではアップローダーでも予定所有者でなければ更新不可")
        void 異常系_PERSONAL予定の非所有アップローダーは更新不可() {
            ScheduleMediaUploadEntity entity =
                    buildMediaEntity(MEDIA_ID, SCHEDULE_ID, UPLOADER_ID, "IMAGE");
            ScheduleMediaPatchRequest req = buildPatchRequest("越権更新", null, null, null);
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));
            givenSchedule(mockPersonalSchedule(OTHER_USER_ID));

            assertThatThrownBy(() ->
                    scheduleMediaService.updateMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN));
            then(scheduleMediaUploadRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("正常系_PERSONAL予定所有者はuploaderIdがnullでも更新可能")
        void 正常系_PERSONAL予定所有者はアップローダー不在でも更新可能() {
            ScheduleMediaUploadEntity entity =
                    buildMediaEntity(MEDIA_ID, SCHEDULE_ID, null, "IMAGE");
            ScheduleMediaPatchRequest req = buildPatchRequest("所有者更新", null, null, null);
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));
            givenSchedule(mockPersonalSchedule(UPLOADER_ID));
            given(scheduleMediaUploadRepository.save(entity)).willReturn(entity);

            scheduleMediaService.updateMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID, req);

            assertThat(entity.getCaption()).isEqualTo("所有者更新");
        }

        @Test
        @DisplayName("異常系_退会済みアップローダーはTEAMメディアを更新不可")
        void 異常系_退会済みアップローダーは更新不可() {
            ScheduleMediaUploadEntity entity =
                    buildMediaEntity(MEDIA_ID, SCHEDULE_ID, UPLOADER_ID, "IMAGE");
            ScheduleMediaPatchRequest req = buildPatchRequest("退会後更新", null, null, null);
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));
            givenSchedule(mockTeamSchedule(20L));

            assertThatThrownBy(() ->
                    scheduleMediaService.updateMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN));
            then(scheduleMediaUploadRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("異常系_複合PATCHの権限拒否時は先行フィールドも変更しない")
        void 異常系_複合PATCH拒否時は全フィールド不変() {
            ScheduleMediaUploadEntity entity = ScheduleMediaUploadEntity.builder()
                    .id(MEDIA_ID)
                    .scheduleId(SCHEDULE_ID)
                    .uploaderId(UPLOADER_ID)
                    .mediaType("IMAGE")
                    .r2Key("schedules/100/uuid.jpg")
                    .fileName("photo.jpg")
                    .fileSize(1024L)
                    .contentType("image/jpeg")
                    .processingStatus("READY")
                    .caption("変更前")
                    .isExpenseReceipt(true)
                    .build();
            ScheduleMediaPatchRequest req = buildPatchRequest("変更後", null, null, false);
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() ->
                    scheduleMediaService.updateMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN));
            assertThat(entity.getCaption()).isEqualTo("変更前");
            assertThat(entity.getIsExpenseReceipt()).isTrue();
            then(scheduleMediaUploadRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("異常系_削除済み親予定のメディア更新は404")
        void 異常系_削除済み親予定のメディア更新は404() {
            ScheduleMediaUploadEntity entity =
                    buildMediaEntity(MEDIA_ID, SCHEDULE_ID, UPLOADER_ID, "IMAGE");
            ScheduleEntity deletedSchedule = mockScheduleEntity();
            given(deletedSchedule.getDeletedAt()).willReturn(LocalDateTime.now());
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));
            givenSchedule(deletedSchedule);

            assertThatThrownBy(() -> scheduleMediaService.updateMedia(
                    SCHEDULE_ID, MEDIA_ID, UPLOADER_ID,
                    buildPatchRequest("更新", null, null, null)))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
            then(scheduleMediaUploadRepository).should(never()).save(any());
        }
    }

    // ==================== deleteMedia ====================

    @Nested
    @DisplayName("deleteMedia")
    class DeleteMedia {

        @Test
        @DisplayName("正常系_自分のメディアを削除")
        void 正常系_自分のメディアを削除() {
            // given
            ScheduleMediaUploadEntity entity = buildMediaEntity(MEDIA_ID, SCHEDULE_ID, UPLOADER_ID, "IMAGE");
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));

            // when
            scheduleMediaService.deleteMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID);

            // then
            then(r2StorageService).should().delete(entity.getR2Key());
            then(scheduleMediaUploadRepository).should().delete(entity);
        }

        @Test
        @DisplayName("正常系_ADMIN が他人のメディアを削除")
        void 正常系_ADMINが他人のメディアを削除() {
            // given: entity の uploaderId は OTHER_USER_ID（UPLOADER_ID でなく）
            ScheduleMediaUploadEntity entity = buildMediaEntity(MEDIA_ID, SCHEDULE_ID, OTHER_USER_ID, "IMAGE");
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));
            givenSchedule(mockTeamSchedule(10L));
            given(accessControlService.isAdmin(UPLOADER_ID, 10L, "TEAM")).willReturn(true);

            // when: isAdminOrDeputy=true で削除
            scheduleMediaService.deleteMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID);

            // then
            then(r2StorageService).should().delete(entity.getR2Key());
            then(scheduleMediaUploadRepository).should().delete(entity);
        }

        @Test
        @DisplayName("異常系_他人のメディアを MEMBER が削除_403")
        void 異常系_他人のメディアをMEMBERが削除_403() {
            // given
            ScheduleMediaUploadEntity entity = buildMediaEntity(MEDIA_ID, SCHEDULE_ID, OTHER_USER_ID, "IMAGE");
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));
            givenSchedule(mockTeamSchedule(10L));

            // when / then
            assertThatThrownBy(() ->
                    scheduleMediaService.deleteMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN));
            then(r2StorageService).should(never()).delete(anyString());
            then(scheduleMediaUploadRepository).should(never()).delete(any());
        }

        @Test
        @DisplayName("正常系_サムネイルも一緒に削除される")
        void 正常系_サムネイルも削除される() {
            // given: サムネイルキーを持つ VIDEO エンティティ
            ScheduleMediaUploadEntity entity = ScheduleMediaUploadEntity.builder()
                    .id(MEDIA_ID)
                    .scheduleId(SCHEDULE_ID)
                    .uploaderId(UPLOADER_ID)
                    .mediaType("VIDEO")
                    .r2Key("schedules/100/uuid.mp4")
                    .thumbnailR2Key("schedules/100/uuid-thumb.jpg")
                    .fileName("movie.mp4")
                    .fileSize(200L * 1024 * 1024)
                    .contentType("video/mp4")
                    .processingStatus("READY")
                    .build();
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));

            // when
            scheduleMediaService.deleteMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID);

            // then: メインファイルとサムネイルの両方が削除される
            then(r2StorageService).should().delete("schedules/100/uuid.mp4");
            then(r2StorageService).should().delete("schedules/100/uuid-thumb.jpg");
            then(scheduleMediaUploadRepository).should().delete(entity);
        }

        @Test
        @DisplayName("正常系_R2 削除失敗しても DB 削除は続行")
        void 正常系_R2削除失敗してもDB削除は続行() {
            // given
            ScheduleMediaUploadEntity entity = buildMediaEntity(MEDIA_ID, SCHEDULE_ID, UPLOADER_ID, "IMAGE");
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));
            willThrow(new RuntimeException("R2接続エラー"))
                    .given(r2StorageService).delete(anyString());

            // when: R2 削除が失敗しても例外は飛ばない
            scheduleMediaService.deleteMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID);

            // then: DB 削除は呼ばれる
            then(scheduleMediaUploadRepository).should().delete(entity);
        }

        @Test
        @DisplayName("異常系_メディアが存在しない_404")
        void 異常系_メディアが存在しない_404() {
            // given
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() ->
                    scheduleMediaService.deleteMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("異常系_scheduleId 不一致_404")
        void 異常系_scheduleId不一致_404() {
            // given
            ScheduleMediaUploadEntity entity = buildMediaEntity(MEDIA_ID, 999L, UPLOADER_ID, "IMAGE");
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));

            // when / then
            assertThatThrownBy(() ->
                    scheduleMediaService.deleteMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("正常系_SYSTEM_ADMINが他人のメディアを強制削除")
        void 正常系_SYSTEM_ADMINが他人のメディアを強制削除() {
            ScheduleMediaUploadEntity entity =
                    buildMediaEntity(MEDIA_ID, SCHEDULE_ID, OTHER_USER_ID, "IMAGE");
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));
            givenSchedule(mockPersonalSchedule(OTHER_USER_ID));
            given(accessControlService.isSystemAdmin(UPLOADER_ID)).willReturn(true);

            scheduleMediaService.deleteMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID);

            then(r2StorageService).should().delete(entity.getR2Key());
            then(scheduleMediaUploadRepository).should().delete(entity);
            then(storageQuotaService).should().recordDeletion(
                    StorageScopeType.PERSONAL, OTHER_USER_ID, entity.getFileSize(),
                    StorageFeatureType.SCHEDULE_MEDIA, "schedule_media_uploads", MEDIA_ID, UPLOADER_ID);
        }

        @Test
        @DisplayName("異常系_PERSONAL予定ではアップローダーでも予定所有者でなければ削除不可")
        void 異常系_PERSONAL予定の非所有アップローダーは削除不可() {
            ScheduleMediaUploadEntity entity =
                    buildMediaEntity(MEDIA_ID, SCHEDULE_ID, UPLOADER_ID, "IMAGE");
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));
            givenSchedule(mockPersonalSchedule(OTHER_USER_ID));

            assertThatThrownBy(() ->
                    scheduleMediaService.deleteMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN));
            then(r2StorageService).shouldHaveNoInteractions();
            then(scheduleMediaUploadRepository).should(never()).delete(any());
        }

        @Test
        @DisplayName("異常系_親予定が存在しない場合は本人削除でも404")
        void 異常系_親予定不存在は本人削除でも404() {
            ScheduleMediaUploadEntity entity =
                    buildMediaEntity(MEDIA_ID, SCHEDULE_ID, UPLOADER_ID, "IMAGE");
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    scheduleMediaService.deleteMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
            then(r2StorageService).shouldHaveNoInteractions();
            then(scheduleMediaUploadRepository).should(never()).delete(any());
            then(storageQuotaService).shouldHaveNoInteractions();
        }
    }

    // ==================== cleanupOrphanMedia ====================

    @Nested
    @DisplayName("cleanupOrphanMedia")
    class CleanupOrphanMedia {

        @Test
        @DisplayName("正常系_孤立メディアを削除（サムネイルも含む）")
        void 正常系_孤立メディアを削除() {
            // given
            ScheduleMediaUploadEntity orphanImage = ScheduleMediaUploadEntity.builder()
                    .id(1L)
                    .uploaderId(UPLOADER_ID)
                    .mediaType("IMAGE")
                    .r2Key("schedules/100/orphan-image.jpg")
                    .fileName("orphan-image.jpg")
                    .fileSize(1024L)
                    .contentType("image/jpeg")
                    .processingStatus("READY")
                    .build();

            ScheduleMediaUploadEntity orphanVideo = ScheduleMediaUploadEntity.builder()
                    .id(2L)
                    .uploaderId(UPLOADER_ID)
                    .mediaType("VIDEO")
                    .r2Key("schedules/100/orphan-video.mp4")
                    .thumbnailR2Key("schedules/100/orphan-video-thumb.jpg")
                    .fileName("orphan-video.mp4")
                    .fileSize(100L * 1024 * 1024)
                    .contentType("video/mp4")
                    .processingStatus("PENDING")
                    .build();

            given(scheduleMediaUploadRepository.findOrphanMedia(any(LocalDateTime.class)))
                    .willReturn(List.of(orphanImage, orphanVideo));

            // when
            scheduleMediaService.cleanupOrphanMedia();

            // then: R2 からメインファイルと VIDEO のサムネイルも削除
            then(r2StorageService).should().delete("schedules/100/orphan-image.jpg");
            then(r2StorageService).should().delete("schedules/100/orphan-video.mp4");
            then(r2StorageService).should().delete("schedules/100/orphan-video-thumb.jpg");
            // DB から一括削除
            then(scheduleMediaUploadRepository).should()
                    .deleteAll(List.of(orphanImage, orphanVideo));
        }
    }

    // ==================== F13 Phase 4-γ: StorageQuota 統合テスト ====================

    @Nested
    @DisplayName("F13 Phase 4-γ: StorageQuota 統合")
    class StorageQuotaIntegration {

        /** チームスコープを持つ ScheduleEntity モック */
        private ScheduleEntity teamSchedule() {
            return ScheduleEntity.builder().id(SCHEDULE_ID).teamId(50L).build();
        }

        /** 組織スコープを持つ ScheduleEntity モック */
        private ScheduleEntity orgSchedule() {
            return ScheduleEntity.builder().id(SCHEDULE_ID).organizationId(60L).build();
        }

        /** 個人スコープを持つ ScheduleEntity モック */
        private ScheduleEntity personalSchedule() {
            return ScheduleEntity.builder().id(SCHEDULE_ID).build();
        }

        @Test
        @DisplayName("正常系_IMAGE アップロード: checkQuota → recordUpload が呼ばれる（TEAM スコープ）")
        void 正常系_IMAGE_checkQuota_recordUpload_TEAM() {
            // given
            ScheduleEntity schedule = teamSchedule();
            ScheduleMediaUploadUrlRequest req = buildRequest("IMAGE", "image/jpeg", 1024L * 1024, "photo.jpg");
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));
            given(scheduleMediaUploadRepository.countByScheduleIdAndMediaType(SCHEDULE_ID, "IMAGE"))
                    .willReturn(0);
            given(r2StorageService.generateUploadUrl(anyString(), eq("image/jpeg"), any()))
                    .willReturn(new PresignedUploadResult(
                            "https://r2.example.com/presigned-put-url",
                            "schedules/100/uuid.jpg",
                            600L));
            given(scheduleMediaUploadRepository.save(any(ScheduleMediaUploadEntity.class)))
                    .willAnswer(inv -> {
                        ScheduleMediaUploadEntity entity = inv.getArgument(0);
                        return ScheduleMediaUploadEntity.builder()
                                .id(MEDIA_ID)
                                .scheduleId(entity.getScheduleId())
                                .uploaderId(entity.getUploaderId())
                                .mediaType(entity.getMediaType())
                                .r2Key(entity.getR2Key())
                                .fileName(entity.getFileName())
                                .fileSize(entity.getFileSize())
                                .contentType(entity.getContentType())
                                .processingStatus(entity.getProcessingStatus())
                                .build();
                    });

            // when
            scheduleMediaService.generateUploadUrl(SCHEDULE_ID, UPLOADER_ID, req);

            // then: TEAM スコープで checkQuota / recordUpload が呼ばれる
            then(storageQuotaService).should()
                    .checkQuota(StorageScopeType.TEAM, 50L, 1024L * 1024);
            then(storageQuotaService).should()
                    .recordUpload(eq(StorageScopeType.TEAM), eq(50L), eq(1024L * 1024),
                            eq(StorageFeatureType.SCHEDULE_MEDIA),
                            eq("schedule_media_uploads"), eq(MEDIA_ID), eq(UPLOADER_ID));
        }

        @Test
        @DisplayName("正常系_VIDEO アップロード: checkQuota → recordUpload が呼ばれる（ORGANIZATION スコープ）")
        void 正常系_VIDEO_checkQuota_recordUpload_ORG() {
            // given
            ScheduleEntity schedule = orgSchedule();
            ScheduleMediaUploadUrlRequest req = buildRequest("VIDEO", "video/mp4", 200L * 1024 * 1024, "movie.mp4");
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));
            given(scheduleMediaUploadRepository.countByScheduleIdAndMediaType(SCHEDULE_ID, "VIDEO"))
                    .willReturn(0);
            given(multipartUploadService.startUpload(eq(UPLOADER_ID), any(StartMultipartUploadRequest.class)))
                    .willReturn(new StartMultipartUploadResponse(
                            "test-upload-id",
                            "schedules/100/uuid.mp4",
                            1,
                            10L * 1024 * 1024));
            given(scheduleMediaUploadRepository.save(any(ScheduleMediaUploadEntity.class)))
                    .willAnswer(inv -> {
                        ScheduleMediaUploadEntity entity = inv.getArgument(0);
                        return ScheduleMediaUploadEntity.builder()
                                .id(MEDIA_ID)
                                .scheduleId(entity.getScheduleId())
                                .uploaderId(entity.getUploaderId())
                                .mediaType(entity.getMediaType())
                                .r2Key(entity.getR2Key())
                                .fileName(entity.getFileName())
                                .fileSize(entity.getFileSize())
                                .contentType(entity.getContentType())
                                .processingStatus(entity.getProcessingStatus())
                                .build();
                    });

            // when
            scheduleMediaService.generateUploadUrl(SCHEDULE_ID, UPLOADER_ID, req);

            // then: ORGANIZATION スコープで checkQuota / recordUpload が呼ばれる
            then(storageQuotaService).should()
                    .checkQuota(StorageScopeType.ORGANIZATION, 60L, 200L * 1024 * 1024);
            then(storageQuotaService).should()
                    .recordUpload(eq(StorageScopeType.ORGANIZATION), eq(60L), eq(200L * 1024 * 1024),
                            eq(StorageFeatureType.SCHEDULE_MEDIA),
                            eq("schedule_media_uploads"), eq(MEDIA_ID), eq(UPLOADER_ID));
        }

        @Test
        @DisplayName("異常系_クォータ超過: generateUploadUrl で 409 がスローされる")
        void 異常系_クォータ超過_generateUploadUrl_409() {
            // given
            ScheduleEntity schedule = teamSchedule();
            ScheduleMediaUploadUrlRequest req = buildRequest("IMAGE", "image/jpeg", 1024L, "photo.jpg");
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));
            given(scheduleMediaUploadRepository.countByScheduleIdAndMediaType(SCHEDULE_ID, "IMAGE"))
                    .willReturn(0);
            willThrow(new StorageQuotaExceededException(
                    StorageScopeType.TEAM, 50L, 1024L,
                    5L * 1024 * 1024 * 1024L, 5L * 1024 * 1024 * 1024L))
                    .given(storageQuotaService)
                    .checkQuota(eq(StorageScopeType.TEAM), eq(50L), anyLong());

            // when / then: クォータ超過 → 409 Conflict
            assertThatThrownBy(() ->
                    scheduleMediaService.generateUploadUrl(SCHEDULE_ID, UPLOADER_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.CONFLICT));
            // recordUpload は呼ばれない
            then(storageQuotaService).should(never())
                    .recordUpload(any(), anyLong(), anyLong(), any(), anyString(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("正常系_deleteMedia: recordDeletion が呼ばれる（TEAM スコープ）")
        void 正常系_deleteMedia_recordDeletion_TEAM() {
            // given
            long fileSize = 1024L * 1024;
            ScheduleMediaUploadEntity entity = buildMediaEntity(MEDIA_ID, SCHEDULE_ID, UPLOADER_ID, "IMAGE");
            ScheduleEntity schedule = teamSchedule();
            given(scheduleMediaUploadRepository.findById(MEDIA_ID)).willReturn(Optional.of(entity));
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));
            given(accessControlService.isMember(UPLOADER_ID, 50L, "TEAM")).willReturn(true);

            // when
            scheduleMediaService.deleteMedia(SCHEDULE_ID, MEDIA_ID, UPLOADER_ID);

            // then: TEAM スコープで recordDeletion が呼ばれる
            then(storageQuotaService).should()
                    .recordDeletion(eq(StorageScopeType.TEAM), eq(50L), eq(fileSize),
                            eq(StorageFeatureType.SCHEDULE_MEDIA),
                            eq("schedule_media_uploads"), eq(MEDIA_ID), eq(UPLOADER_ID));
        }

        @Test
        @DisplayName("正常系_resolveScope: TEAM スコープ判定")
        void resolveScope_TEAM() {
            ScheduleEntity schedule = teamSchedule();
            ScheduleMediaService.ScopeResolution scope =
                    scheduleMediaService.resolveScope(schedule, UPLOADER_ID);
            assertThat(scope.scopeType()).isEqualTo(StorageScopeType.TEAM);
            assertThat(scope.scopeId()).isEqualTo(50L);
        }

        @Test
        @DisplayName("正常系_resolveScope: ORGANIZATION スコープ判定")
        void resolveScope_ORGANIZATION() {
            ScheduleEntity schedule = orgSchedule();
            ScheduleMediaService.ScopeResolution scope =
                    scheduleMediaService.resolveScope(schedule, UPLOADER_ID);
            assertThat(scope.scopeType()).isEqualTo(StorageScopeType.ORGANIZATION);
            assertThat(scope.scopeId()).isEqualTo(60L);
        }

        @Test
        @DisplayName("正常系_resolveScope: PERSONAL スコープ（個人スケジュール）")
        void resolveScope_PERSONAL() {
            ScheduleEntity schedule = personalSchedule();
            ScheduleMediaService.ScopeResolution scope =
                    scheduleMediaService.resolveScope(schedule, UPLOADER_ID);
            assertThat(scope.scopeType()).isEqualTo(StorageScopeType.PERSONAL);
            assertThat(scope.scopeId()).isEqualTo(UPLOADER_ID);
        }
    }
}
