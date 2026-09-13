package com.mannschaft.app.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.acl.StorageAclAttachmentBinding;
import com.mannschaft.app.common.storage.acl.StorageAclContentReference;
import com.mannschaft.app.common.storage.acl.StorageAclDownloadRequest;
import com.mannschaft.app.common.storage.acl.StorageAclScope;
import com.mannschaft.app.common.storage.acl.StorageAclService;
import com.mannschaft.app.common.storage.acl.StorageAccessService;
import com.mannschaft.app.service.dto.BulkCreateServiceRecordRequest;
import com.mannschaft.app.service.dto.CreateServiceRecordRequest;
import com.mannschaft.app.service.dto.RegisterAttachmentRequest;
import com.mannschaft.app.service.dto.ServiceRecordResponse;
import com.mannschaft.app.service.dto.UploadUrlRequest;
import com.mannschaft.app.service.entity.ServiceRecordAttachmentEntity;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    @Mock private StorageAclService storageAclService;
    @Mock private StorageAccessService storageAccessService;
    @Mock private AccessControlService accessControlService;

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
    @DisplayName("read attachments")
    class ReadAttachments {

        @Test
        @DisplayName("ACL一致添付だけを署名URL付きで返し、不一致添付は省略する")
        void ACL一致だけを返す() {
            ServiceRecordEntity record = createRecordEntity(ServiceRecordStatus.CONFIRMED);
            ReflectionTestUtils.setField(record, "id", RECORD_ID);
            ServiceRecordAttachmentEntity allowed = ServiceRecordAttachmentEntity.builder()
                    .serviceRecordId(RECORD_ID).fileKey("service/allowed")
                    .fileName("allowed.pdf").contentType("application/pdf").fileSize(10L).build();
            ServiceRecordAttachmentEntity denied = ServiceRecordAttachmentEntity.builder()
                    .serviceRecordId(RECORD_ID).fileKey("service/denied")
                    .fileName("denied.pdf").contentType("application/pdf").fileSize(20L).build();
            ReflectionTestUtils.setField(allowed, "id", 20L);
            ReflectionTestUtils.setField(denied, "id", 21L);
            given(recordRepository.findByIdAndTeamId(RECORD_ID, TEAM_ID)).willReturn(Optional.of(record));
            given(valueRepository.findByServiceRecordId(RECORD_ID)).willReturn(List.of());
            given(fieldRepository.findByTeamIdOrderBySortOrder(TEAM_ID)).willReturn(List.of());
            given(attachmentRepository.findByServiceRecordIdOrderBySortOrder(RECORD_ID))
                    .willReturn(List.of(allowed, denied));
            given(storageAccessService.generateDownloadUrlsForList(any(), any()))
                    .willReturn(Map.of(allowed.getFileKey(), "https://download/allowed"));

            ServiceRecordResponse result = service.getRecord(TEAM_ID, RECORD_ID, USER_ID);

            assertThat(result.getAttachments()).singleElement().satisfies(attachment -> {
                assertThat(attachment.getId()).isEqualTo(20L);
                assertThat(attachment.getDownloadUrl()).isEqualTo("https://download/allowed");
            });
            verify(storageAccessService).generateDownloadUrlsForList(eq(List.of(
                    new StorageAclDownloadRequest(
                            allowed.getFileKey(), StorageAclScope.team(TEAM_ID),
                            new StorageAclContentReference("SERVICE_RECORD", RECORD_ID.toString()),
                            new StorageAclAttachmentBinding("SERVICE_RECORD_ATTACHMENT", "20")),
                    new StorageAclDownloadRequest(
                            denied.getFileKey(), StorageAclScope.team(TEAM_ID),
                            new StorageAclContentReference("SERVICE_RECORD", RECORD_ID.toString()),
                            new StorageAclAttachmentBinding("SERVICE_RECORD_ATTACHMENT", "21")))),
                    any(Duration.class));
        }

        @Test
        @DisplayName("署名ストレージ障害を握り潰さず伝播する")
        void storageFailurePropagates() {
            ServiceRecordEntity record = createRecordEntity(ServiceRecordStatus.CONFIRMED);
            ReflectionTestUtils.setField(record, "id", RECORD_ID);
            ServiceRecordAttachmentEntity attachment = ServiceRecordAttachmentEntity.builder()
                    .serviceRecordId(RECORD_ID).fileKey("service/key")
                    .fileName("a.pdf").contentType("application/pdf").fileSize(10L).build();
            ReflectionTestUtils.setField(attachment, "id", 20L);
            given(recordRepository.findByIdAndTeamId(RECORD_ID, TEAM_ID)).willReturn(Optional.of(record));
            given(valueRepository.findByServiceRecordId(RECORD_ID)).willReturn(List.of());
            given(fieldRepository.findByTeamIdOrderBySortOrder(TEAM_ID)).willReturn(List.of());
            given(attachmentRepository.findByServiceRecordIdOrderBySortOrder(RECORD_ID))
                    .willReturn(List.of(attachment));
            given(storageAccessService.generateDownloadUrlsForList(any(), any()))
                    .willThrow(new IllegalStateException("storage unavailable"));

            assertThatThrownBy(() -> service.getRecord(TEAM_ID, RECORD_ID, USER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("storage unavailable");
        }
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

            assertThatThrownBy(() -> service.confirmRecord(TEAM_ID, RECORD_ID, USER_ID))
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
            service.deleteRecord(TEAM_ID, RECORD_ID, USER_ID);
            verify(recordRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: 記録不在でSERVICE_RECORD_001例外")
        void 削除_不在_例外() {
            given(recordRepository.findByIdAndTeamId(RECORD_ID, TEAM_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.deleteRecord(TEAM_ID, RECORD_ID, USER_ID))
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
        @DisplayName("正常系: 検証済み MIME を署名URLとACL台帳へ同じ値で渡す")
        void 検証済みMIMEを署名とACLで統一する() {
            ServiceRecordEntity entity = createRecordEntity(ServiceRecordStatus.DRAFT);
            given(recordRepository.findByIdAndTeamId(RECORD_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(attachmentRepository.countByServiceRecordId(RECORD_ID)).willReturn(0L);
            given(storageService.generateUploadUrl(any(), any(), any(Duration.class)))
                    .willReturn(new PresignedUploadResult("https://signed", "service-key", 600L));
            UploadUrlRequest request = new UploadUrlRequest();
            request.setFileName("evidence.pdf");
            request.setContentType("application/pdf");
            request.setFileSize(1_000L);

            var result = service.generateUploadUrl(TEAM_ID, RECORD_ID, USER_ID, request);

            verify(storageService).generateUploadUrl(any(), org.mockito.ArgumentMatchers.eq("application/pdf"),
                    any(Duration.class));
            verify(storageAclService).registerPending(eq(result.getFileKey()), eq(USER_ID),
                    eq(StorageAclScope.team(TEAM_ID)), eq("application/pdf"), any(Duration.class),
                    eq(new StorageAclContentReference("SERVICE_RECORD", RECORD_ID.toString())));
        }

        @Test
        @DisplayName("異常系: 許可されていないコンテンツタイプでSERVICE_RECORD_017例外")
        void アップロード_不正タイプ_例外() {
            ServiceRecordEntity entity = createRecordEntity(ServiceRecordStatus.DRAFT);
            given(recordRepository.findByIdAndTeamId(RECORD_ID, TEAM_ID)).willReturn(Optional.of(entity));
            UploadUrlRequest request = new UploadUrlRequest();
            request.setContentType("text/plain");
            request.setFileSize(1000L);

            assertThatThrownBy(() -> service.generateUploadUrl(TEAM_ID, RECORD_ID, USER_ID, request))
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

            assertThatThrownBy(() -> service.generateUploadUrl(TEAM_ID, RECORD_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("SERVICE_RECORD_016"));
        }
    }

    @Nested
    @DisplayName("registerAttachment")
    class RegisterAttachment {
        @Test
        @DisplayName("正常系: owner・scope・親参照・添付束縛を一致させてACLをclaimする")
        void ACLのowner_scope_親参照_添付束縛を一致させてclaimする() {
            // given
            ServiceRecordEntity record = createRecordEntity(ServiceRecordStatus.DRAFT);
            ServiceRecordAttachmentEntity saved = ServiceRecordAttachmentEntity.builder()
                    .serviceRecordId(RECORD_ID)
                    .fileKey("service-records/1/10/evidence.pdf")
                    .fileName("evidence.pdf")
                    .contentType("application/pdf")
                    .fileSize(1_000L)
                    .build();
            Long attachmentId = 20L;
            ReflectionTestUtils.setField(saved, "id", attachmentId);
            RegisterAttachmentRequest request = new RegisterAttachmentRequest();
            request.setFileKey(saved.getFileKey());
            request.setFileName(saved.getFileName());
            request.setContentType(saved.getContentType());
            request.setFileSize(saved.getFileSize());
            given(recordRepository.findByIdAndTeamId(RECORD_ID, TEAM_ID)).willReturn(Optional.of(record));
            given(attachmentRepository.countByServiceRecordId(RECORD_ID)).willReturn(0L);
            given(attachmentRepository.save(any(ServiceRecordAttachmentEntity.class))).willReturn(saved);

            // when
            service.registerAttachment(TEAM_ID, RECORD_ID, USER_ID, request);

            // then
            verify(storageAclService).claimPending(eq(saved.getFileKey()), eq(USER_ID),
                    eq(StorageAclScope.team(TEAM_ID)),
                    eq(new StorageAclContentReference("SERVICE_RECORD", RECORD_ID.toString())),
                    eq(new StorageAclAttachmentBinding("SERVICE_RECORD_ATTACHMENT", attachmentId.toString())));
        }
    }
}
