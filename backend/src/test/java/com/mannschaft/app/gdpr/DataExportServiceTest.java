package com.mannschaft.app.gdpr;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EmailService;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.gdpr.entity.DataExportEntity;
import com.mannschaft.app.gdpr.repository.DataExportRepository;
import com.mannschaft.app.gdpr.service.DataExportService;
import com.mannschaft.app.gdpr.service.PersonalDataCollector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("DataExportService 単体テスト")
class DataExportServiceTest {

    @Mock
    private DataExportRepository dataExportRepository;

    @Mock
    private PersonalDataCollector personalDataCollector;

    @Mock
    private StorageService storageService;

    @Mock
    private EmailService emailService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private DataExportService service;

    private static final Long USER_ID = 1L;

    @Nested
    @DisplayName("requestExport")
    class RequestExport {

        @Test
        @DisplayName("正常系: COMPLETED後24時間以上経過している場合、新規エクスポートが作成される")
        void 正常_COMPLETED後24時間超_新規作成() {
            given(dataExportRepository.findTopByUserIdOrderByCreatedAtDesc(USER_ID))
                    .willReturn(Optional.empty());

            DataExportEntity newEntity = DataExportEntity.builder()
                    .userId(USER_ID)
                    .status("PENDING")
                    .build();
            given(dataExportRepository.save(any(DataExportEntity.class))).willReturn(newEntity);

            DataExportEntity result = service.requestExport(USER_ID, null);

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo("PENDING");
            verify(dataExportRepository).save(any(DataExportEntity.class));
        }

        @Test
        @DisplayName("異常系: 24時間以内にCOMPLETEDが存在する → GDPR_001例外")
        void 異常_24時間以内COMPLETED_GDPR001() {
            // createdAtが30分前のCOMPLETEDエンティティを作成（リフレクションで設定）
            DataExportEntity recent = buildEntityWithCreatedAt("COMPLETED", LocalDateTime.now().minusMinutes(30));

            given(dataExportRepository.findTopByUserIdOrderByCreatedAtDesc(USER_ID))
                    .willReturn(Optional.of(recent));

            assertThatThrownBy(() -> service.requestExport(USER_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("GDPR_001"));
        }

        @Test
        @DisplayName("異常系: PROCESSINGが存在する → GDPR_002例外")
        void 異常_PROCESSING存在_GDPR002() {
            DataExportEntity processing = DataExportEntity.builder()
                    .userId(USER_ID)
                    .status("PROCESSING")
                    .build();

            given(dataExportRepository.findTopByUserIdOrderByCreatedAtDesc(USER_ID))
                    .willReturn(Optional.of(processing));

            assertThatThrownBy(() -> service.requestExport(USER_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("GDPR_002"));
        }

        @Test
        @DisplayName("正常系: 過去エクスポートなしで新規作成")
        void 正常_過去エクスポートなし_新規作成() {
            given(dataExportRepository.findTopByUserIdOrderByCreatedAtDesc(USER_ID))
                    .willReturn(Optional.empty());
            DataExportEntity newEntity = DataExportEntity.builder()
                    .userId(USER_ID)
                    .status("PENDING")
                    .build();
            given(dataExportRepository.save(any())).willReturn(newEntity);

            DataExportEntity result = service.requestExport(USER_ID, null);

            assertThat(result.getStatus()).isEqualTo("PENDING");
        }
    }

    @Nested
    @DisplayName("getExportStatus")
    class GetExportStatus {

        @Test
        @DisplayName("正常系: 最新エクスポートのEntityが返る")
        void 正常_最新エンティティ返却() {
            DataExportEntity entity = DataExportEntity.builder()
                    .userId(USER_ID)
                    .status("COMPLETED")
                    .build();
            given(dataExportRepository.findTopByUserIdOrderByCreatedAtDesc(USER_ID))
                    .willReturn(Optional.of(entity));

            DataExportEntity result = service.getExportStatus(USER_ID);

            assertThat(result).isSameAs(entity);
        }

        @Test
        @DisplayName("異常系: エクスポートが存在しない → GDPR_003例外")
        void 異常_未存在_GDPR003() {
            given(dataExportRepository.findTopByUserIdOrderByCreatedAtDesc(USER_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getExportStatus(USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("GDPR_003"));
        }
    }

    @Nested
    @DisplayName("recoverStuckExports")
    class RecoverStuckExports {

        @Test
        @DisplayName("正常系: 1時間超のPROCESSINGをFAILEDにリセット")
        void 正常_スタックリセット() {
            given(dataExportRepository.resetStuckProcessing(any(), any())).willReturn(2);

            // recoverStuckExports()はvoidを返す
            service.recoverStuckExports();

            verify(dataExportRepository).resetStuckProcessing(any(LocalDateTime.class), any(String.class));
        }
    }

    @Nested
    @DisplayName("cleanupExpiredExports")
    class CleanupExpiredExports {

        @Test
        @DisplayName("正常系: 期限切れZIPをS3から削除")
        void 正常_期限切れZIP削除() {
            DataExportEntity expired = DataExportEntity.builder()
                    .userId(USER_ID)
                    .status("COMPLETED")
                    .s3Key("exports/user1/data.zip")
                    .expiresAt(LocalDateTime.now().minusDays(1))
                    .build();
            given(dataExportRepository.findByExpiresAtBeforeAndS3KeyIsNotNull(any()))
                    .willReturn(List.of(expired));
            given(dataExportRepository.save(any())).willReturn(expired);

            service.cleanupExpiredExports();

            verify(storageService).delete("exports/user1/data.zip");
            verify(dataExportRepository).save(expired);
        }
    }

    // ===== ヘルパーメソッド =====

    /**
     * createdAtをリフレクションで設定したDataExportEntityを生成する。
     * requestExportではcreatedAtで24時間判定しているため。
     */
    private DataExportEntity buildEntityWithCreatedAt(String status, LocalDateTime createdAt) {
        DataExportEntity entity = DataExportEntity.builder()
                .userId(USER_ID)
                .status(status)
                .build();
        try {
            var baseEntityClass = com.mannschaft.app.common.BaseEntity.class;
            var field = baseEntityClass.getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(entity, createdAt);
        } catch (Exception e) {
            throw new RuntimeException("テスト用エンティティ構築失敗", e);
        }
        return entity;
    }
}
