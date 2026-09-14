package com.mannschaft.app.budget.service;

import com.mannschaft.app.budget.BudgetMapper;
import com.mannschaft.app.budget.BudgetTransactionType;
import com.mannschaft.app.budget.dto.RegisterAttachmentRequest;
import com.mannschaft.app.budget.entity.BudgetTransactionAttachmentEntity;
import com.mannschaft.app.budget.entity.BudgetTransactionEntity;
import com.mannschaft.app.budget.repository.BudgetConfigRepository;
import com.mannschaft.app.budget.repository.BudgetTransactionAttachmentRepository;
import com.mannschaft.app.budget.repository.BudgetTransactionRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.S3ObjectDeleteEvent;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.common.storage.acl.StorageAclAttachmentBinding;
import com.mannschaft.app.common.storage.acl.StorageAclContentReference;
import com.mannschaft.app.common.storage.acl.StorageAclScope;
import com.mannschaft.app.common.storage.acl.StorageAclService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/** {@link BudgetTransactionService} のストレージACL引数を固定する単体テスト。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BudgetTransactionService 単体テスト")
class BudgetTransactionServiceTest {

    private static final Long TRANSACTION_ID = 710L;
    private static final Long TEAM_ID = 711L;
    private static final Long USER_ID = 712L;

    @Mock
    private BudgetTransactionRepository transactionRepository;
    @Mock
    private BudgetTransactionAttachmentRepository attachmentRepository;
    @Mock
    private BudgetConfigRepository configRepository;
    @Mock
    private BudgetFiscalYearService fiscalYearService;
    @Mock
    private BudgetCategoryService categoryService;
    @Mock
    private BudgetMapper budgetMapper;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private DomainEventPublisher domainEventPublisher;
    @Mock
    private StorageService storageService;
    @Mock
    private StorageAclService storageAclService;

    @InjectMocks
    private BudgetTransactionService service;

    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    @Nested
    @DisplayName("generateUploadUrl")
    class GenerateUploadUrl {
        @Test
        @DisplayName("正常系: owner・scope・親参照を一致させてPENDING ACLを登録する")
        void ACLのowner_scope_親参照を一致させてPENDING登録する() {
            // given
            BudgetTransactionEntity transaction = transaction();
            given(transactionRepository.findById(TRANSACTION_ID)).willReturn(Optional.of(transaction));
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(storageService.generateUploadUrl(anyString(), eq("application/pdf"), any(Duration.class)))
                    .willReturn(new PresignedUploadResult(
                            "https://storage.example/upload", "budget/canonical-key", 900L));

            // when
            var response = service.generateUploadUrl(TRANSACTION_ID, "receipt.pdf", "application/pdf");

            // then
            verify(storageAclService).registerPending(eq(response.s3Key()), eq(USER_ID),
                    eq(StorageAclScope.team(TEAM_ID)), eq("application/pdf"), any(Duration.class),
                    eq(new StorageAclContentReference("BUDGET_TRANSACTION", TRANSACTION_ID.toString())));
        }
    }

    @Nested
    @DisplayName("registerAttachment")
    class RegisterAttachment {
        @Test
        @DisplayName("正常系: owner・scope・親参照・添付束縛を一致させてACLをclaimする")
        void ACLのowner_scope_親参照_添付束縛を一致させてclaimする() {
            // given
            BudgetTransactionEntity transaction = transaction();
            BudgetTransactionAttachmentEntity saved = BudgetTransactionAttachmentEntity.builder()
                    .transactionId(TRANSACTION_ID)
                    .originalFilename("receipt.pdf")
                    .mimeType("application/pdf")
                    .fileSize(1_024L)
                    .fileKey("budget/attachments/710/receipt.pdf")
                    .build();
            Long attachmentId = 713L;
            ReflectionTestUtils.setField(saved, "id", attachmentId);
            given(transactionRepository.findById(TRANSACTION_ID)).willReturn(Optional.of(transaction));
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(attachmentRepository.save(any(BudgetTransactionAttachmentEntity.class))).willReturn(saved);
            RegisterAttachmentRequest request = new RegisterAttachmentRequest(TRANSACTION_ID, "receipt.pdf",
                    "application/pdf", 1_024L, saved.getFileKey());

            // when
            service.registerAttachment(request);

            // then
            verify(storageAclService).claimPending(eq(saved.getFileKey()), eq(USER_ID),
                    eq(StorageAclScope.team(TEAM_ID)),
                    eq(new StorageAclContentReference("BUDGET_TRANSACTION", TRANSACTION_ID.toString())),
                    eq(new StorageAclAttachmentBinding("BUDGET_TRANSACTION_ATTACHMENT", attachmentId.toString())));
        }
    }

    @Nested
    @DisplayName("deleteAttachment")
    class DeleteAttachment {
        @Test
        @DisplayName("ACLを一度だけ失効し、物理削除をcommit後イベントへ委譲する")
        void ACLを一度だけ失効し物理削除イベントを発行する() {
            BudgetTransactionEntity transaction = transaction();
            BudgetTransactionAttachmentEntity attachment = BudgetTransactionAttachmentEntity.builder()
                    .transactionId(TRANSACTION_ID)
                    .originalFilename("receipt.pdf")
                    .mimeType("application/pdf")
                    .fileSize(1_024L)
                    .fileKey("budget/attachments/710/receipt.pdf")
                    .build();
            Long attachmentId = 713L;
            ReflectionTestUtils.setField(attachment, "id", attachmentId);
            given(transactionRepository.findById(TRANSACTION_ID)).willReturn(Optional.of(transaction));
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(attachmentRepository.findById(attachmentId)).willReturn(Optional.of(attachment));

            service.deleteAttachment(TRANSACTION_ID, attachmentId);

            StorageAclAttachmentBinding binding = new StorageAclAttachmentBinding(
                    "BUDGET_TRANSACTION_ATTACHMENT", attachmentId.toString());
            verify(storageAclService, times(1)).releaseClaimed(attachment.getFileKey(), binding);
            verify(attachmentRepository).delete(attachment);
            verify(domainEventPublisher).publish(argThat(event ->
                    event instanceof S3ObjectDeleteEvent deleteEvent
                            && deleteEvent.s3Keys().equals(List.of(attachment.getFileKey()))));
            verify(storageService, never()).delete(anyString());
        }
    }

    private BudgetTransactionEntity transaction() {
        BudgetTransactionEntity transaction = BudgetTransactionEntity.builder()
                .fiscalYearId(1L)
                .categoryId(2L)
                .scopeType("TEAM")
                .scopeId(TEAM_ID)
                .transactionType(BudgetTransactionType.INCOME)
                .amount(BigDecimal.ONE)
                .transactionDate(LocalDate.of(2026, 1, 1))
                .title("ACL検証用取引")
                .recordedBy(USER_ID)
                .build();
        ReflectionTestUtils.setField(transaction, "id", TRANSACTION_ID);
        return transaction;
    }
}
