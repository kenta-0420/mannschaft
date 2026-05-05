package com.mannschaft.app.common.storage.quota;

import com.mannschaft.app.common.storage.StorageProperties;
import com.mannschaft.app.common.storage.quota.entity.StorageSubscriptionEntity;
import com.mannschaft.app.common.storage.quota.entity.StorageUsageLogEntity;
import com.mannschaft.app.common.storage.quota.repository.StorageSubscriptionRepository;
import com.mannschaft.app.common.storage.quota.repository.StorageUsageLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F13 Phase 4-ζ ドリフト検出バッチのユニットテスト。
 *
 * <p>Phase 4-α〜δ で追加した {@link StorageFeatureType#PERSONAL_TIMETABLE_NOTES} /
 * {@link StorageFeatureType#SCHEDULE_MEDIA} を含む全 feature_type が
 * {@link StorageDriftDetectionBatchService#FEATURE_PREFIX_MAP} に登録されていることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StorageDriftDetectionBatchService ユニットテスト")
class StorageDriftDetectionBatchServiceTest {

    private static final Long SUBSCRIPTION_ID = 1L;
    private static final Long SCOPE_ID = 100L;
    private static final String BUCKET = "mannschaft-storage";

    @Mock private S3Client s3Client;
    @Mock private StorageProperties storageProperties;
    @Mock private StorageSubscriptionRepository subscriptionRepository;
    @Mock private StorageUsageLogRepository usageLogRepository;

    @InjectMocks private StorageDriftDetectionBatchService service;

    // ==================== プレフィックスマッピング検証 ====================

    @Nested
    @DisplayName("FEATURE_PREFIX_MAP — feature_type プレフィックス登録確認")
    class FeaturePrefixMapTest {

        @Test
        @DisplayName("全 StorageFeatureType が FEATURE_PREFIX_MAP に登録されている")
        void 全feature_typeがマッピングに登録済み() {
            Map<StorageFeatureType, List<String>> map = service.getFeaturePrefixMap();

            for (StorageFeatureType featureType : StorageFeatureType.values()) {
                assertThat(map)
                        .as("feature_type %s が FEATURE_PREFIX_MAP に未登録", featureType)
                        .containsKey(featureType);
                assertThat(map.get(featureType))
                        .as("feature_type %s のプレフィックスが空", featureType)
                        .isNotEmpty();
            }
        }

        @Test
        @DisplayName("Phase 4-α 追加: PERSONAL_TIMETABLE_NOTES が user/ プレフィックスにマッピングされている")
        void PERSONAL_TIMETABLE_NOTESのプレフィックスが正しい() {
            Map<StorageFeatureType, List<String>> map = service.getFeaturePrefixMap();

            assertThat(map.get(StorageFeatureType.PERSONAL_TIMETABLE_NOTES))
                    .contains("user/");
        }

        @Test
        @DisplayName("Phase 4-α 追加: SCHEDULE_MEDIA が schedules/ プレフィックスにマッピングされている")
        void SCHEDULE_MEDIAのプレフィックスが正しい() {
            Map<StorageFeatureType, List<String>> map = service.getFeaturePrefixMap();

            assertThat(map.get(StorageFeatureType.SCHEDULE_MEDIA))
                    .contains("schedules/");
        }

        @Test
        @DisplayName("既存 feature_type のプレフィックスが正しい")
        void 既存feature_typeのプレフィックスが正しい() {
            Map<StorageFeatureType, List<String>> map = service.getFeaturePrefixMap();

            assertThat(map.get(StorageFeatureType.TIMELINE)).contains("timeline/");
            assertThat(map.get(StorageFeatureType.GALLERY)).contains("gallery/");
            assertThat(map.get(StorageFeatureType.FILE_SHARING)).contains("files/");
            assertThat(map.get(StorageFeatureType.CHAT)).contains("chat/");
            assertThat(map.get(StorageFeatureType.CMS)).contains("blog/");
            assertThat(map.get(StorageFeatureType.CIRCULATION)).contains("circulation/");
            assertThat(map.get(StorageFeatureType.BULLETIN)).contains("bulletin/");
        }
    }

    // ==================== R2 バイト集計 ====================

    @Nested
    @DisplayName("listAllObjectsBytes — R2 オブジェクト列挙")
    class ListAllObjectsBytesTest {

        @BeforeEach
        void setUp() {
            given(storageProperties.getBucket()).willReturn(BUCKET);
        }

        @Test
        @DisplayName("正常系: 単一ページで全オブジェクトを集計できる")
        void 正常系_単一ページ() {
            ListObjectsV2Response response = ListObjectsV2Response.builder()
                    .contents(
                            S3Object.builder().key("timeline/TEAM/1/file1.mp4").size(1000L).build(),
                            S3Object.builder().key("timeline/TEAM/1/file2.jpg").size(2000L).build()
                    )
                    .isTruncated(false)
                    .build();
            given(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).willReturn(response);

            long bytes = service.listAllObjectsBytes("timeline/");

            assertThat(bytes).isEqualTo(3000L);
            verify(s3Client, times(1)).listObjectsV2(any(ListObjectsV2Request.class));
        }

        @Test
        @DisplayName("正常系: ページング複数回でも全合計を集計できる")
        void 正常系_複数ページ() {
            ListObjectsV2Response page1 = ListObjectsV2Response.builder()
                    .contents(S3Object.builder().key("files/TEAM/1/a.pdf").size(500L).build())
                    .isTruncated(true)
                    .nextContinuationToken("token1")
                    .build();
            ListObjectsV2Response page2 = ListObjectsV2Response.builder()
                    .contents(S3Object.builder().key("files/TEAM/1/b.pdf").size(300L).build())
                    .isTruncated(false)
                    .build();
            given(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                    .willReturn(page1, page2);

            long bytes = service.listAllObjectsBytes("files/");

            assertThat(bytes).isEqualTo(800L);
            verify(s3Client, times(2)).listObjectsV2(any(ListObjectsV2Request.class));
        }

        @Test
        @DisplayName("除外: thumbnails/ プレフィックスのオブジェクトはカウントしない")
        void 除外_thumbnailsはカウント対象外() {
            ListObjectsV2Response response = ListObjectsV2Response.builder()
                    .contents(
                            S3Object.builder().key("timeline/TEAM/1/video.mp4").size(1000L).build(),
                            S3Object.builder().key("timeline/TEAM/1/thumbnails/thumb.jpg").size(9999L).build()
                    )
                    .isTruncated(false)
                    .build();
            given(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).willReturn(response);

            long bytes = service.listAllObjectsBytes("timeline/");

            // thumbnails/ は除外 → 1000 のみ
            assertThat(bytes).isEqualTo(1000L);
        }

        @Test
        @DisplayName("除外: tmp/ プレフィックスのオブジェクトはカウントしない")
        void 除外_tmpはカウント対象外() {
            ListObjectsV2Response response = ListObjectsV2Response.builder()
                    .contents(
                            S3Object.builder().key("chat/uuid-123/img.png").size(200L).build(),
                            S3Object.builder().key("tmp/uuid-upload.bin").size(99999L).build()
                    )
                    .isTruncated(false)
                    .build();
            given(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).willReturn(response);

            long bytes = service.listAllObjectsBytes("chat/");

            // tmp/ は除外 → 200 のみ
            assertThat(bytes).isEqualTo(200L);
        }

        @Test
        @DisplayName("正常系: オブジェクトが 0 件の場合は 0 を返す")
        void 正常系_オブジェクトなし() {
            ListObjectsV2Response response = ListObjectsV2Response.builder()
                    .contents(List.of())
                    .isTruncated(false)
                    .build();
            given(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).willReturn(response);

            long bytes = service.listAllObjectsBytes("user/");

            assertThat(bytes).isEqualTo(0L);
        }
    }

    // ==================== ドリフト修正ロジック ====================

    @Nested
    @DisplayName("correctSubscriptionDrift — ドリフト修正")
    class CorrectSubscriptionDriftTest {

        @Test
        @DisplayName("正常系: 差異が 1MB 未満の場合は修正しない")
        void 正常系_差異が閾値未満なら修正不要() {
            StorageSubscriptionEntity sub = StorageSubscriptionEntity.builder()
                    .id(SUBSCRIPTION_ID).scopeType("TEAM").scopeId(SCOPE_ID)
                    .planId(1L).usedBytes(1000L).fileCount(1).build();

            // r2BytesByFeature の合計 = 1000L（差異 0 < 1MB）
            Map<StorageFeatureType, Long> r2Bytes = Map.of(
                    StorageFeatureType.TIMELINE, 1000L
            );

            int corrected = service.correctSubscriptionDrift(sub, r2Bytes);

            assertThat(corrected).isEqualTo(0);
            verify(subscriptionRepository, never()).save(any());
            verify(usageLogRepository, never()).save(any());
        }

        @Test
        @DisplayName("正常系: 差異が 1MB 以上の場合は used_bytes を修正し DRIFT_CORRECTION ログを挿入")
        void 正常系_差異が閾値以上なら修正する() {
            // DB の used_bytes = 5MB
            StorageSubscriptionEntity sub = StorageSubscriptionEntity.builder()
                    .id(SUBSCRIPTION_ID).scopeType("TEAM").scopeId(SCOPE_ID)
                    .planId(1L).usedBytes(5L * 1024 * 1024).fileCount(5).build();

            given(subscriptionRepository.save(any(StorageSubscriptionEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // R2 の実測値 = 10MB（差異 5MB > 1MB のしきい値）
            Map<StorageFeatureType, Long> r2Bytes = Map.of(
                    StorageFeatureType.TIMELINE, 10L * 1024 * 1024
            );

            int corrected = service.correctSubscriptionDrift(sub, r2Bytes);

            assertThat(corrected).isEqualTo(1);

            // used_bytes が 10MB に修正されている
            assertThat(sub.getUsedBytes()).isEqualTo(10L * 1024 * 1024);

            // DRIFT_CORRECTION ログが挿入されている
            ArgumentCaptor<StorageUsageLogEntity> logCaptor =
                    ArgumentCaptor.forClass(StorageUsageLogEntity.class);
            verify(usageLogRepository).save(logCaptor.capture());
            StorageUsageLogEntity log = logCaptor.getValue();
            assertThat(log.getAction()).isEqualTo(StorageActionType.DRIFT_CORRECTION.name());
            assertThat(log.getFeatureType()).isEqualTo(StorageActionType.DRIFT_CORRECTION.name());
            assertThat(log.getDeltaBytes()).isEqualTo(5L * 1024 * 1024);
            assertThat(log.getAfterBytes()).isEqualTo(10L * 1024 * 1024);
            assertThat(log.getSubscriptionId()).isEqualTo(SUBSCRIPTION_ID);
            assertThat(log.getActorId()).isNull();  // バッチなので NULL
        }

        @Test
        @DisplayName("正常系: DB の used_bytes が R2 より大きい場合（マイナス差分）も修正する")
        void 正常系_DBがR2より大きい場合も修正する() {
            // DB の used_bytes = 20MB（実際より多く記録されているケース）
            StorageSubscriptionEntity sub = StorageSubscriptionEntity.builder()
                    .id(SUBSCRIPTION_ID).scopeType("PERSONAL").scopeId(SCOPE_ID)
                    .planId(1L).usedBytes(20L * 1024 * 1024).fileCount(3).build();

            given(subscriptionRepository.save(any(StorageSubscriptionEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // R2 の実測値 = 5MB
            Map<StorageFeatureType, Long> r2Bytes = Map.of(
                    StorageFeatureType.PERSONAL_TIMETABLE_NOTES, 5L * 1024 * 1024
            );

            int corrected = service.correctSubscriptionDrift(sub, r2Bytes);

            assertThat(corrected).isEqualTo(1);
            // 5MB に修正されている
            assertThat(sub.getUsedBytes()).isEqualTo(5L * 1024 * 1024);
        }

        @Test
        @DisplayName("正常系: PERSONAL_TIMETABLE_NOTES / SCHEDULE_MEDIA の集計も含めてドリフト修正できる")
        void 正常系_Phase4α追加feature_typeも修正対象() {
            StorageSubscriptionEntity sub = StorageSubscriptionEntity.builder()
                    .id(SUBSCRIPTION_ID).scopeType("PERSONAL").scopeId(SCOPE_ID)
                    .planId(1L).usedBytes(0L).fileCount(0).build();

            given(subscriptionRepository.save(any(StorageSubscriptionEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // PERSONAL_TIMETABLE_NOTES と SCHEDULE_MEDIA の両方が R2 に存在
            Map<StorageFeatureType, Long> r2Bytes = Map.of(
                    StorageFeatureType.PERSONAL_TIMETABLE_NOTES, 3L * 1024 * 1024,
                    StorageFeatureType.SCHEDULE_MEDIA, 2L * 1024 * 1024
            );

            int corrected = service.correctSubscriptionDrift(sub, r2Bytes);

            assertThat(corrected).isEqualTo(1);
            // 合計 5MB に修正
            assertThat(sub.getUsedBytes()).isEqualTo(5L * 1024 * 1024);
        }
    }

    // ==================== execute() 全体フロー ====================

    @Nested
    @DisplayName("execute — バッチ全体フロー")
    class ExecuteTest {

        @BeforeEach
        void setUp() {
            given(storageProperties.getBucket()).willReturn(BUCKET);
        }

        @Test
        @DisplayName("正常系: サブスクリプションが空の場合はスキップ（エラーなし）")
        void 正常系_サブスクリプションが空() {
            // R2 は全プレフィックスで 0 バイト
            ListObjectsV2Response emptyResponse = ListObjectsV2Response.builder()
                    .contents(List.of())
                    .isTruncated(false)
                    .build();
            given(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).willReturn(emptyResponse);
            given(subscriptionRepository.findAll()).willReturn(List.of());

            service.execute();

            verify(usageLogRepository, never()).save(any());
        }

        @Test
        @DisplayName("正常系: 差異のないサブスクリプションは修正しない")
        void 正常系_差異なしは修正しない() {
            ListObjectsV2Response emptyResponse = ListObjectsV2Response.builder()
                    .contents(List.of())
                    .isTruncated(false)
                    .build();
            given(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).willReturn(emptyResponse);

            StorageSubscriptionEntity sub = StorageSubscriptionEntity.builder()
                    .id(SUBSCRIPTION_ID).scopeType("TEAM").scopeId(SCOPE_ID)
                    .planId(1L).usedBytes(0L).fileCount(0).build();
            given(subscriptionRepository.findAll()).willReturn(List.of(sub));

            service.execute();

            verify(subscriptionRepository, never()).save(any());
            verify(usageLogRepository, never()).save(any());
        }
    }
}
