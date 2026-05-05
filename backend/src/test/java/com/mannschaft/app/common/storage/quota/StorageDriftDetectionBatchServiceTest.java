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
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F13 Phase 5-c ドリフト検出バッチのユニットテスト。
 *
 * <p>スコープ別プレフィックス走査への移行を検証する。
 * Phase 5-c の新 API（{@link StorageDriftDetectionBatchService#buildScopePrefix}、
 * {@link StorageDriftDetectionBatchService#buildOldScopePrefix}、
 * {@link StorageDriftDetectionBatchService#sumR2BytesForSubscription}、
 * {@link StorageDriftDetectionBatchService#correctSubscriptionDrift(StorageSubscriptionEntity, long)}）
 * に対応したテストを網羅する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StorageDriftDetectionBatchService ユニットテスト（Phase 5-c）")
class StorageDriftDetectionBatchServiceTest {

    private static final Long SUBSCRIPTION_ID = 1L;
    private static final Long SCOPE_ID = 100L;
    private static final String BUCKET = "mannschaft-storage";

    @Mock private S3Client s3Client;
    @Mock private StorageProperties storageProperties;
    @Mock private StorageSubscriptionRepository subscriptionRepository;
    @Mock private StorageUsageLogRepository usageLogRepository;

    @InjectMocks private StorageDriftDetectionBatchService service;

    // ==================== スコープ別プレフィックス構築 ====================

    @Nested
    @DisplayName("buildScopePrefix — スコープ別プレフィックス構築")
    class BuildScopePrefixTest {

        @Test
        @DisplayName("CHAT の TEAM スコーププレフィックスが正しい")
        void CHAT_TEAMスコープのプレフィックスが正しい() {
            String prefix = service.buildScopePrefix(StorageFeatureType.CHAT, "TEAM", 50L);
            assertThat(prefix).isEqualTo("chat/TEAM/50/");
        }

        @Test
        @DisplayName("FILE_SHARING の ORGANIZATION スコーププレフィックスが正しい")
        void FILE_SHARING_ORGANIZATIONスコープのプレフィックスが正しい() {
            String prefix = service.buildScopePrefix(StorageFeatureType.FILE_SHARING, "ORGANIZATION", 200L);
            assertThat(prefix).isEqualTo("files/ORGANIZATION/200/");
        }

        @Test
        @DisplayName("PERSONAL_TIMETABLE_NOTES の専用パターンが適用される")
        void PERSONAL_TIMETABLE_NOTESの専用パターンが正しい() {
            String prefix = service.buildScopePrefix(StorageFeatureType.PERSONAL_TIMETABLE_NOTES, "PERSONAL", 100L);
            assertThat(prefix).isEqualTo("user/PERSONAL/100/timetable-notes/");
        }

        @Test
        @DisplayName("TIMELINE の TEAM スコーププレフィックスが正しい")
        void TIMELINE_TEAMスコープのプレフィックスが正しい() {
            String prefix = service.buildScopePrefix(StorageFeatureType.TIMELINE, "TEAM", 10L);
            assertThat(prefix).isEqualTo("timeline/TEAM/10/");
        }

        @Test
        @DisplayName("SCHEDULE_MEDIA の TEAM スコーププレフィックスが正しい")
        void SCHEDULE_MEDIA_TEAMスコープのプレフィックスが正しい() {
            String prefix = service.buildScopePrefix(StorageFeatureType.SCHEDULE_MEDIA, "TEAM", 30L);
            assertThat(prefix).isEqualTo("schedules/TEAM/30/");
        }

        @Test
        @DisplayName("全 feature_type が FEATURE_ROOT_MAP または専用パターンを持つ（網羅確認）")
        void 全feature_typeのプレフィックスが構築できる() {
            // どの feature_type でも NullPointerException が発生しないこと
            for (StorageFeatureType featureType : StorageFeatureType.values()) {
                String prefix = service.buildScopePrefix(featureType, "TEAM", 1L);
                assertThat(prefix)
                        .as("feature_type %s のプレフィックスが null または空", featureType)
                        .isNotNull()
                        .isNotEmpty()
                        .endsWith("/");
            }
        }
    }

    // ==================== 旧パスプレフィックス構築（移行期モード） ====================

    @Nested
    @DisplayName("buildOldScopePrefix — 旧パスプレフィックス構築")
    class BuildOldScopePrefixTest {

        @Test
        @DisplayName("PERSONAL_TIMETABLE_NOTES の旧パスが正しい")
        void PERSONAL_TIMETABLE_NOTESの旧パスが正しい() {
            Optional<String> result = service.buildOldScopePrefix(
                    StorageFeatureType.PERSONAL_TIMETABLE_NOTES, "PERSONAL", 100L);
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo("user/100/timetable-notes/");
        }

        @Test
        @DisplayName("CHAT の旧パスはスコープ特定不可のため empty を返す")
        void CHATの旧パスはemptyを返す() {
            Optional<String> result = service.buildOldScopePrefix(
                    StorageFeatureType.CHAT, "TEAM", 50L);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("FILE_SHARING の旧パスはスコープ特定不可のため empty を返す")
        void FILE_SHARINGの旧パスはemptyを返す() {
            Optional<String> result = service.buildOldScopePrefix(
                    StorageFeatureType.FILE_SHARING, "TEAM", 50L);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("CIRCULATION の旧パスはスコープ特定不可のため empty を返す")
        void CIRCULATIONの旧パスはemptyを返す() {
            Optional<String> result = service.buildOldScopePrefix(
                    StorageFeatureType.CIRCULATION, "ORGANIZATION", 200L);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("SCHEDULE_MEDIA の旧パスはスコープ特定不可のため empty を返す")
        void SCHEDULE_MEDIAの旧パスはemptyを返す() {
            Optional<String> result = service.buildOldScopePrefix(
                    StorageFeatureType.SCHEDULE_MEDIA, "TEAM", 50L);
            assertThat(result).isEmpty();
        }
    }

    // ==================== R2 バイト集計（プレフィックス単位） ====================

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

            long bytes = service.listAllObjectsBytes("timeline/TEAM/1/");

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

            long bytes = service.listAllObjectsBytes("files/TEAM/1/");

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

            long bytes = service.listAllObjectsBytes("timeline/TEAM/1/");

            // thumbnails/ は除外 → 1000 のみ
            assertThat(bytes).isEqualTo(1000L);
        }

        @Test
        @DisplayName("除外: tmp/ プレフィックスのオブジェクトはカウントしない")
        void 除外_tmpはカウント対象外() {
            ListObjectsV2Response response = ListObjectsV2Response.builder()
                    .contents(
                            S3Object.builder().key("chat/TEAM/1/img.png").size(200L).build(),
                            S3Object.builder().key("tmp/uuid-upload.bin").size(99999L).build()
                    )
                    .isTruncated(false)
                    .build();
            given(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).willReturn(response);

            long bytes = service.listAllObjectsBytes("chat/TEAM/1/");

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

            long bytes = service.listAllObjectsBytes("user/PERSONAL/100/timetable-notes/");

            assertThat(bytes).isEqualTo(0L);
        }
    }

    // ==================== スコープ別 R2 バイト集計 ====================

    @Nested
    @DisplayName("sumR2BytesForSubscription — スコープ別 R2 集計")
    class SumR2BytesForSubscriptionTest {

        @BeforeEach
        void setUp() {
            given(storageProperties.getBucket()).willReturn(BUCKET);
        }

        @Test
        @DisplayName("正常系: TEAM サブスクリプションはスコープ別プレフィックスを走査する")
        void 正常系_TEAMサブスクリプションはスコープ別プレフィックスを走査する() {
            ListObjectsV2Response emptyResponse = ListObjectsV2Response.builder()
                    .contents(List.of()).isTruncated(false).build();
            given(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).willReturn(emptyResponse);

            StorageSubscriptionEntity sub = StorageSubscriptionEntity.builder()
                    .id(1L).scopeType("TEAM").scopeId(50L).planId(1L).usedBytes(0L).fileCount(0).build();

            service.sumR2BytesForSubscription(sub);

            // 全 feature_type 分（9件）呼ばれることを確認
            int featureTypeCount = StorageFeatureType.values().length;
            ArgumentCaptor<ListObjectsV2Request> captor = ArgumentCaptor.forClass(ListObjectsV2Request.class);
            verify(s3Client, times(featureTypeCount)).listObjectsV2(captor.capture());

            List<String> prefixes = captor.getAllValues().stream()
                    .map(ListObjectsV2Request::prefix).toList();

            // スコープ別プレフィックスが含まれていることを確認
            assertThat(prefixes).contains("chat/TEAM/50/");
            assertThat(prefixes).contains("files/TEAM/50/");
            assertThat(prefixes).contains("timeline/TEAM/50/");
            assertThat(prefixes).contains("schedules/TEAM/50/");
            // PERSONAL_TIMETABLE_NOTES は専用パターン
            assertThat(prefixes).contains("user/PERSONAL/50/timetable-notes/");
        }

        @Test
        @DisplayName("移行期モード無効: 旧パスは走査しない")
        void 移行期モード無効時は旧パスを走査しない() {
            // デフォルトは migrationModeEnabled=false
            ReflectionTestUtils.setField(service, "migrationModeEnabled", false);

            ListObjectsV2Response emptyResponse = ListObjectsV2Response.builder()
                    .contents(List.of()).isTruncated(false).build();
            given(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).willReturn(emptyResponse);

            StorageSubscriptionEntity sub = StorageSubscriptionEntity.builder()
                    .id(1L).scopeType("PERSONAL").scopeId(100L).planId(1L).usedBytes(0L).fileCount(0).build();

            service.sumR2BytesForSubscription(sub);

            // 新パスのみ（feature_type 数 = 9 回）
            int featureTypeCount = StorageFeatureType.values().length;
            ArgumentCaptor<ListObjectsV2Request> captor = ArgumentCaptor.forClass(ListObjectsV2Request.class);
            verify(s3Client, times(featureTypeCount)).listObjectsV2(captor.capture());

            List<String> prefixes = captor.getAllValues().stream()
                    .map(ListObjectsV2Request::prefix).toList();

            // 旧パスは走査しない
            assertThat(prefixes).doesNotContain("user/100/timetable-notes/");
            // 新パスは走査する
            assertThat(prefixes).contains("user/PERSONAL/100/timetable-notes/");
        }

        @Test
        @DisplayName("移行期モード有効: PERSONAL_TIMETABLE_NOTES の旧パスも走査する")
        void 移行期モード有効時はPERSONAL_TIMETABLE_NOTESの旧パスも走査する() {
            ReflectionTestUtils.setField(service, "migrationModeEnabled", true);

            ListObjectsV2Response emptyResponse = ListObjectsV2Response.builder()
                    .contents(List.of()).isTruncated(false).build();
            given(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).willReturn(emptyResponse);

            StorageSubscriptionEntity sub = StorageSubscriptionEntity.builder()
                    .id(1L).scopeType("PERSONAL").scopeId(100L).planId(1L).usedBytes(0L).fileCount(0).build();

            service.sumR2BytesForSubscription(sub);

            // 新パス 9件 + 旧パス 1件（PERSONAL_TIMETABLE_NOTES のみ）= 10件
            int featureTypeCount = StorageFeatureType.values().length;
            ArgumentCaptor<ListObjectsV2Request> captor = ArgumentCaptor.forClass(ListObjectsV2Request.class);
            verify(s3Client, times(featureTypeCount + 1)).listObjectsV2(captor.capture());

            List<String> prefixes = captor.getAllValues().stream()
                    .map(ListObjectsV2Request::prefix).toList();

            // 新パスと旧パスの両方が走査される
            assertThat(prefixes).contains("user/PERSONAL/100/timetable-notes/");  // 新パス
            assertThat(prefixes).contains("user/100/timetable-notes/");            // 旧パス
        }

        @Test
        @DisplayName("正常系: 各プレフィックスのバイト数を合算して返す")
        void 正常系_バイト数が合算される() {
            // CHAT プレフィックス "chat/TEAM/50/" は 5MB を返す、それ以外は 0
            ListObjectsV2Response chatResponse = ListObjectsV2Response.builder()
                    .contents(S3Object.builder().key("chat/TEAM/50/msg.png").size(5L * 1024 * 1024).build())
                    .isTruncated(false)
                    .build();
            ListObjectsV2Response emptyResponse = ListObjectsV2Response.builder()
                    .contents(List.of()).isTruncated(false).build();

            // 全プレフィックスのうち "chat/TEAM/50/" だけ有効データを返す
            given(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                    .willAnswer(inv -> {
                        ListObjectsV2Request req = inv.getArgument(0);
                        if ("chat/TEAM/50/".equals(req.prefix())) {
                            return chatResponse;
                        }
                        return emptyResponse;
                    });

            StorageSubscriptionEntity sub = StorageSubscriptionEntity.builder()
                    .id(1L).scopeType("TEAM").scopeId(50L).planId(1L).usedBytes(0L).fileCount(0).build();

            long total = service.sumR2BytesForSubscription(sub);

            assertThat(total).isEqualTo(5L * 1024 * 1024);
        }
    }

    // ==================== ドリフト修正ロジック ====================

    @Nested
    @DisplayName("correctSubscriptionDrift — ドリフト修正（long シグネチャ）")
    class CorrectSubscriptionDriftTest {

        @Test
        @DisplayName("正常系: 差異が 1MB 未満の場合は修正しない")
        void 正常系_差異が閾値未満なら修正不要() {
            StorageSubscriptionEntity sub = StorageSubscriptionEntity.builder()
                    .id(SUBSCRIPTION_ID).scopeType("TEAM").scopeId(SCOPE_ID)
                    .planId(1L).usedBytes(1000L).fileCount(1).build();

            // R2 実測値 = 1000L（差異 0 < 1MB）
            int corrected = service.correctSubscriptionDrift(sub, 1000L);

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
            int corrected = service.correctSubscriptionDrift(sub, 10L * 1024 * 1024);

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
            int corrected = service.correctSubscriptionDrift(sub, 5L * 1024 * 1024);

            assertThat(corrected).isEqualTo(1);
            // 5MB に修正されている
            assertThat(sub.getUsedBytes()).isEqualTo(5L * 1024 * 1024);
        }

        @Test
        @DisplayName("正常系: DRIFT_CORRECTION ログの各フィールドが正しい")
        void 正常系_DRIFTCORRECTIONログのフィールドが正しい() {
            StorageSubscriptionEntity sub = StorageSubscriptionEntity.builder()
                    .id(SUBSCRIPTION_ID).scopeType("ORGANIZATION").scopeId(SCOPE_ID)
                    .planId(1L).usedBytes(0L).fileCount(0).build();

            given(subscriptionRepository.save(any(StorageSubscriptionEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // 差異 3MB
            long r2Bytes = 3L * 1024 * 1024;
            service.correctSubscriptionDrift(sub, r2Bytes);

            ArgumentCaptor<StorageUsageLogEntity> logCaptor =
                    ArgumentCaptor.forClass(StorageUsageLogEntity.class);
            verify(usageLogRepository).save(logCaptor.capture());
            StorageUsageLogEntity log = logCaptor.getValue();

            assertThat(log.getSubscriptionId()).isEqualTo(SUBSCRIPTION_ID);
            assertThat(log.getDeltaBytes()).isEqualTo(r2Bytes);
            assertThat(log.getAfterBytes()).isEqualTo(r2Bytes);
            assertThat(log.getFeatureType()).isEqualTo(StorageActionType.DRIFT_CORRECTION.name());
            assertThat(log.getReferenceType()).isEqualTo("storage_subscriptions");
            assertThat(log.getReferenceId()).isEqualTo(SUBSCRIPTION_ID);
            assertThat(log.getAction()).isEqualTo(StorageActionType.DRIFT_CORRECTION.name());
            assertThat(log.getActorId()).isNull();
        }
    }

    // ==================== execute() 全体フロー ====================

    @Nested
    @DisplayName("execute — バッチ全体フロー")
    class ExecuteTest {

        @Test
        @DisplayName("正常系: サブスクリプションが空の場合はスキップ（エラーなし）")
        void 正常系_サブスクリプションが空() {
            // サブスクリプションが空なので R2 呼び出しは発生しない
            given(subscriptionRepository.findAll()).willReturn(List.of());

            service.execute();

            verify(usageLogRepository, never()).save(any());
        }

        @Test
        @DisplayName("正常系: 差異のないサブスクリプションは修正しない")
        void 正常系_差異なしは修正しない() {
            given(storageProperties.getBucket()).willReturn(BUCKET);

            // 全プレフィックスで 0 バイト
            ListObjectsV2Response emptyResponse = ListObjectsV2Response.builder()
                    .contents(List.of()).isTruncated(false).build();
            given(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).willReturn(emptyResponse);

            // DB も 0 バイト（差異なし）
            StorageSubscriptionEntity sub = StorageSubscriptionEntity.builder()
                    .id(SUBSCRIPTION_ID).scopeType("TEAM").scopeId(SCOPE_ID)
                    .planId(1L).usedBytes(0L).fileCount(0).build();
            given(subscriptionRepository.findAll()).willReturn(List.of(sub));

            service.execute();

            verify(subscriptionRepository, never()).save(any());
            verify(usageLogRepository, never()).save(any());
        }

        @Test
        @DisplayName("正常系: 差異があるサブスクリプションは修正する")
        void 正常系_差異ありは修正する() {
            given(storageProperties.getBucket()).willReturn(BUCKET);

            // R2 に 10MB のデータが存在
            ListObjectsV2Response dataResponse = ListObjectsV2Response.builder()
                    .contents(S3Object.builder().key("chat/TEAM/100/file.mp4").size(10L * 1024 * 1024).build())
                    .isTruncated(false)
                    .build();
            ListObjectsV2Response emptyResponse = ListObjectsV2Response.builder()
                    .contents(List.of()).isTruncated(false).build();

            given(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                    .willAnswer(inv -> {
                        ListObjectsV2Request req = inv.getArgument(0);
                        if ("chat/TEAM/100/".equals(req.prefix())) {
                            return dataResponse;
                        }
                        return emptyResponse;
                    });

            // DB は 0 バイト（差異 10MB > しきい値 1MB）
            StorageSubscriptionEntity sub = StorageSubscriptionEntity.builder()
                    .id(SUBSCRIPTION_ID).scopeType("TEAM").scopeId(SCOPE_ID)
                    .planId(1L).usedBytes(0L).fileCount(0).build();
            given(subscriptionRepository.findAll()).willReturn(List.of(sub));
            given(subscriptionRepository.save(any(StorageSubscriptionEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            service.execute();

            // サブスクリプションが修正されている
            verify(subscriptionRepository, times(1)).save(any());
            verify(usageLogRepository, times(1)).save(any());
        }
    }
}
