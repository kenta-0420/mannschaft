package com.mannschaft.app.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.service.dto.BulkCreateServiceRecordRequest;
import com.mannschaft.app.service.dto.CreateServiceRecordRequest;
import com.mannschaft.app.service.dto.ServiceRecordResponse;
import com.mannschaft.app.service.dto.UploadUrlRequest;
import com.mannschaft.app.service.entity.ServiceRecordEntity;
import com.mannschaft.app.service.repository.ServiceRecordAttachmentRepository;
import com.mannschaft.app.service.repository.ServiceRecordFieldRepository;
import com.mannschaft.app.service.repository.ServiceRecordReactionRepository;
import com.mannschaft.app.service.repository.ServiceRecordRepository;
import com.mannschaft.app.service.repository.ServiceRecordSettingsRepository;
import com.mannschaft.app.service.repository.ServiceRecordValueRepository;
import com.mannschaft.app.service.service.ServiceRecordService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceRecordService 単体テスト")
class ServiceRecordServiceTest {

    @Mock private ServiceRecordRepository recordRepository;
    @Mock private ServiceRecordFieldRepository fieldRepository;
    @Mock private ServiceRecordValueRepository valueRepository;
    @Mock private ServiceRecordAttachmentRepository attachmentRepository;
    @Mock private ServiceRecordSettingsRepository settingsRepository;
    @Mock private ServiceRecordReactionRepository reactionRepository;
    @Mock private ServiceRecordMapper mapper;
    @Mock private ObjectMapper objectMapper;
    @Mock private NameResolverService nameResolverService;
    @Mock private StorageService storageService;

    @InjectMocks
    private ServiceRecordService service;

    private static final Long TEAM_ID = 1L;
    private static final Long RECORD_ID = 10L;
    private static final Long USER_ID = 100L;

    private ServiceRecordEntity createRecordEntity(ServiceRecordStatus status) {
        return ServiceRecordEntity.builder()
                .teamId(TEAM_ID).memberUserId(USER_ID).serviceDate(LocalDate.now())
                .title("テスト記録").status(status).build();
    }

    @Nested
    @DisplayName("createRecord")
    class CreateRecord {
        @Test
        @DisplayName("正常系: サービス記録が作成される")
        void 作成_正常_保存() {
            CreateServiceRecordRequest request = new CreateServiceRecordRequest();
            request.setMemberUserId(USER_ID);
            request.setServiceDate(LocalDate.now());
            request.setTitle("テスト");
            ServiceRecordEntity saved = createRecordEntity(ServiceRecordStatus.DRAFT);
            given(recordRepository.save(any())).willReturn(saved);
            given(valueRepository.findByServiceRecordId(any())).willReturn(List.of());
            given(fieldRepository.findByTeamIdOrderBySortOrder(TEAM_ID)).willReturn(List.of());
            given(attachmentRepository.findByServiceRecordIdOrderBySortOrder(any())).willReturn(List.of());

            ServiceRecordResponse result = service.createRecord(TEAM_ID, USER_ID, request);
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("confirmRecord")
    class ConfirmRecord {
        @Test
        @DisplayName("異常系: 既に確定済みでSERVICE_RECORD_010例外")
        void 確定_既確定_例外() {
            ServiceRecordEntity entity = createRecordEntity(ServiceRecordStatus.CONFIRMED);
            given(recordRepository.findByIdAndTeamId(RECORD_ID, TEAM_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.confirmRecord(TEAM_ID, RECORD_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("SERVICE_RECORD_010"));
        }
    }

    @Nested
    @DisplayName("deleteRecord")
    class DeleteRecord {
        @Test
        @DisplayName("正常系: 記録が論理削除される")
        void 削除_正常_論理削除() {
            ServiceRecordEntity entity = createRecordEntity(ServiceRecordStatus.DRAFT);
            given(recordRepository.findByIdAndTeamId(RECORD_ID, TEAM_ID)).willReturn(Optional.of(entity));
            service.deleteRecord(TEAM_ID, RECORD_ID);
            verify(recordRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: 記録不在でSERVICE_RECORD_001例外")
        void 削除_不在_例外() {
            given(recordRepository.findByIdAndTeamId(RECORD_ID, TEAM_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.deleteRecord(TEAM_ID, RECORD_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("SERVICE_RECORD_001"));
        }
    }

    @Nested
    @DisplayName("bulkCreate")
    class BulkCreate {
        @Test
        @DisplayName("異常系: 20件超過でSERVICE_RECORD_019例外")
        void 一括_上限超過_例外() {
            BulkCreateServiceRecordRequest request = new BulkCreateServiceRecordRequest();
            request.setMode("ALL_OR_NOTHING");
            List<CreateServiceRecordRequest> records = new java.util.ArrayList<>();
            for (int i = 0; i < 21; i++) records.add(new CreateServiceRecordRequest());
            request.setRecords(records);

            assertThatThrownBy(() -> service.bulkCreate(TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("SERVICE_RECORD_019"));
        }
    }

    @Nested
    @DisplayName("generateUploadUrl")
    class GenerateUploadUrl {
        @Test
        @DisplayName("異常系: 許可されていないコンテンツタイプでSERVICE_RECORD_017例外")
        void アップロード_不正タイプ_例外() {
            ServiceRecordEntity entity = createRecordEntity(ServiceRecordStatus.DRAFT);
            given(recordRepository.findByIdAndTeamId(RECORD_ID, TEAM_ID)).willReturn(Optional.of(entity));
            UploadUrlRequest request = new UploadUrlRequest();
            request.setContentType("text/plain");
            request.setFileSize(1000L);

            assertThatThrownBy(() -> service.generateUploadUrl(TEAM_ID, RECORD_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("SERVICE_RECORD_017"));
        }

        @Test
        @DisplayName("異常系: ファイルサイズ超過でSERVICE_RECORD_016例外")
        void アップロード_サイズ超過_例外() {
            ServiceRecordEntity entity = createRecordEntity(ServiceRecordStatus.DRAFT);
            given(recordRepository.findByIdAndTeamId(RECORD_ID, TEAM_ID)).willReturn(Optional.of(entity));
            UploadUrlRequest request = new UploadUrlRequest();
            request.setContentType("image/jpeg");
            request.setFileSize(11 * 1024 * 1024L);

            assertThatThrownBy(() -> service.generateUploadUrl(TEAM_ID, RECORD_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("SERVICE_RECORD_016"));
        }
    }
}
