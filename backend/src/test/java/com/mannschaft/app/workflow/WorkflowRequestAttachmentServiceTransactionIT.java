package com.mannschaft.app.workflow;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.StorageErrorCode;
import com.mannschaft.app.common.storage.acl.StorageAclEntity;
import com.mannschaft.app.common.storage.acl.StorageAclMode;
import com.mannschaft.app.common.storage.acl.StorageAclRepository;
import com.mannschaft.app.common.storage.acl.StorageAclScopeType;
import com.mannschaft.app.common.storage.acl.StorageAclStatus;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.workflow.dto.WorkflowAttachmentRegisterRequest;
import com.mannschaft.app.workflow.entity.WorkflowRequestEntity;
import com.mannschaft.app.workflow.entity.WorkflowTemplateEntity;
import com.mannschaft.app.workflow.repository.WorkflowRequestAttachmentRepository;
import com.mannschaft.app.workflow.repository.WorkflowRequestRepository;
import com.mannschaft.app.workflow.repository.WorkflowTemplateRepository;
import com.mannschaft.app.workflow.service.WorkflowRequestAttachmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("WorkflowRequestAttachmentServiceのACL claim失敗時トランザクション")
class WorkflowRequestAttachmentServiceTransactionIT extends AbstractMySqlIntegrationTest {

    private static final Long OWNER_ID = 88001L;
    private static final Long TEAM_ID = 88002L;

    @Autowired
    private WorkflowRequestAttachmentService attachmentService;

    @Autowired
    private WorkflowRequestRepository requestRepository;

    @Autowired
    private WorkflowTemplateRepository templateRepository;

    @Autowired
    private WorkflowRequestAttachmentRepository attachmentRepository;

    @Autowired
    private StorageAclRepository storageAclRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("claim失敗時は添付保存をrollbackしACLをPENDINGのまま残す")
    void claim失敗時は添付保存をrollbackしACLをPENDINGのまま残す() {
        Long workflowRequestId = requiresNewTransaction().execute(status -> {
            WorkflowTemplateEntity template = templateRepository.saveAndFlush(WorkflowTemplateEntity.builder()
                    .scopeType("teams")
                    .scopeId(TEAM_ID)
                    .name("ACL rollback template")
                    .isSealRequired(false)
                    .isActive(true)
                    .sortOrder(0)
                    .build());
            WorkflowRequestEntity workflowRequest = requestRepository.saveAndFlush(WorkflowRequestEntity.builder()
                    .templateId(template.getId())
                    .scopeType("TEAM")
                    .scopeId(TEAM_ID)
                    .title("ACL rollback test")
                    .requestedBy(OWNER_ID)
                    .build());
            return workflowRequest.getId();
        });
        String fileKey = "workflow-attachments/" + workflowRequestId + "/rollback-"
                + System.nanoTime() + ".pdf";
        requiresNewTransaction().executeWithoutResult(status -> storageAclRepository.saveAndFlush(
                StorageAclEntity.builder()
                        .fileKey(fileKey)
                        .ownerId(OWNER_ID)
                        .scopeType(StorageAclScopeType.TEAM)
                        .scopeKey(TEAM_ID.toString())
                        .aclMode(StorageAclMode.CONTENT_BOUND)
                        .contentType("application/pdf")
                        .parentContentReferenceType("WORKFLOW_REQUEST")
                        .parentContentReferenceKey(Long.toString(workflowRequestId + 1L))
                        .status(StorageAclStatus.PENDING)
                        .expiresAt(Instant.now(Clock.systemUTC()).plusSeconds(900))
                        .build()));

        WorkflowAttachmentRegisterRequest request = new WorkflowAttachmentRegisterRequest(
                fileKey, "rollback.pdf", 1024L);

        assertThatThrownBy(() -> attachmentService.registerAttachment(workflowRequestId, OWNER_ID, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(StorageErrorCode.ACL_NOT_FOUND));

        List<?> attachments = requiresNewTransaction().execute(status ->
                attachmentRepository.findByRequestIdOrderByCreatedAtAsc(workflowRequestId));
        StorageAclStatus aclStatus = requiresNewTransaction().execute(status ->
                storageAclRepository.findByFileKey(fileKey).orElseThrow().getStatus());

        assertThat(attachments).isEmpty();
        assertThat(aclStatus).isEqualTo(StorageAclStatus.PENDING);
    }

    private TransactionTemplate requiresNewTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }
}
