package com.mannschaft.app.workflow;

import com.mannschaft.app.workflow.dto.ApproverResponse;
import com.mannschaft.app.workflow.dto.RequestStepResponse;
import com.mannschaft.app.workflow.dto.TemplateFieldResponse;
import com.mannschaft.app.workflow.dto.TemplateStepResponse;
import com.mannschaft.app.workflow.dto.WorkflowAttachmentResponse;
import com.mannschaft.app.workflow.dto.WorkflowCommentResponse;
import com.mannschaft.app.workflow.dto.WorkflowRequestResponse;
import com.mannschaft.app.workflow.dto.WorkflowTemplateResponse;
import com.mannschaft.app.workflow.entity.WorkflowRequestApproverEntity;
import com.mannschaft.app.workflow.entity.WorkflowRequestAttachmentEntity;
import com.mannschaft.app.workflow.entity.WorkflowRequestCommentEntity;
import com.mannschaft.app.workflow.entity.WorkflowRequestEntity;
import com.mannschaft.app.workflow.entity.WorkflowRequestStepEntity;
import com.mannschaft.app.workflow.entity.WorkflowTemplateEntity;
import com.mannschaft.app.workflow.entity.WorkflowTemplateFieldEntity;
import com.mannschaft.app.workflow.entity.WorkflowTemplateStepEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WorkflowMapper} の単体テスト。
 * MapStructImpl を直接インスタンス化してマッピングを検証する。
 */
@DisplayName("WorkflowMapper 単体テスト")
class WorkflowMapperTest {

    private WorkflowMapperImpl mapper;

    @BeforeEach
    void setUp() {
        mapper = new WorkflowMapperImpl();
    }

    // ── ヘルパー ──

    private void setId(Object entity, Long id) throws Exception {
        Field f = entity.getClass().getSuperclass().getDeclaredField("id");
        f.setAccessible(true);
        f.set(entity, id);
    }

    // ── toTemplateResponse ──

    @Nested
    @DisplayName("toTemplateResponse")
    class ToTemplateResponse {

        @Test
        @DisplayName("テンプレートエンティティからレスポンスに変換できる")
        void テンプレートエンティティからレスポンスに変換できる() throws Exception {
            WorkflowTemplateEntity entity = WorkflowTemplateEntity.builder()
                    .scopeType("TEAM")
                    .scopeId(1L)
                    .name("承認フロー")
                    .description("説明")
                    .icon("icon")
                    .color("#FF0000")
                    .isSealRequired(true)
                    .isActive(true)
                    .sortOrder(1)
                    .createdBy(10L)
                    .build();
            setId(entity, 100L);

            WorkflowTemplateResponse result = mapper.toTemplateResponse(entity);

            assertThat(result.getId()).isEqualTo(100L);
            assertThat(result.getScopeType()).isEqualTo("TEAM");
            assertThat(result.getScopeId()).isEqualTo(1L);
            assertThat(result.getName()).isEqualTo("承認フロー");
            assertThat(result.getDescription()).isEqualTo("説明");
            assertThat(result.getIsSealRequired()).isTrue();
            assertThat(result.getIsActive()).isTrue();
            assertThat(result.getCreatedBy()).isEqualTo(10L);
            // steps と fields は ignore なので null
            assertThat(result.getSteps()).isNull();
            assertThat(result.getFields()).isNull();
        }

        @Test
        @DisplayName("null エンティティの場合 null を返す")
        void null_エンティティの場合_null_を返す() {
            assertThat(mapper.toTemplateResponse(null)).isNull();
        }
    }

    // ── toStepResponse ──

    @Nested
    @DisplayName("toStepResponse")
    class ToStepResponse {

        @Test
        @DisplayName("テンプレートステップエンティティからレスポンスに変換できる")
        void テンプレートステップエンティティからレスポンスに変換できる() throws Exception {
            WorkflowTemplateStepEntity entity = WorkflowTemplateStepEntity.builder()
                    .templateId(100L)
                    .stepOrder(1)
                    .name("部長承認")
                    .approvalType(ApprovalType.ALL)
                    .approverType(ApproverType.USER)
                    .approverUserIds("[10,20]")
                    .approverRole("ADMIN")
                    .autoApproveDays((short) 3)
                    .build();
            setId(entity, 200L);

            TemplateStepResponse result = mapper.toStepResponse(entity);

            assertThat(result.getId()).isEqualTo(200L);
            assertThat(result.getTemplateId()).isEqualTo(100L);
            assertThat(result.getStepOrder()).isEqualTo(1);
            assertThat(result.getName()).isEqualTo("部長承認");
            assertThat(result.getApprovalType()).isEqualTo("ALL");
            assertThat(result.getApproverType()).isEqualTo("USER");
            assertThat(result.getApproverUserIds()).isEqualTo("[10,20]");
            assertThat(result.getApproverRole()).isEqualTo("ADMIN");
            assertThat(result.getAutoApproveDays()).isEqualTo((short) 3);
        }

        @Test
        @DisplayName("ステップリストからレスポンスリストに変換できる")
        void ステップリストからレスポンスリストに変換できる() throws Exception {
            WorkflowTemplateStepEntity e1 = WorkflowTemplateStepEntity.builder()
                    .templateId(100L).stepOrder(1).name("ステップ1")
                    .approvalType(ApprovalType.ANY).approverType(ApproverType.ROLE).build();
            WorkflowTemplateStepEntity e2 = WorkflowTemplateStepEntity.builder()
                    .templateId(100L).stepOrder(2).name("ステップ2")
                    .approvalType(ApprovalType.ALL).approverType(ApproverType.USER).build();
            setId(e1, 1L);
            setId(e2, 2L);

            List<TemplateStepResponse> result = mapper.toStepResponseList(List.of(e1, e2));

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getName()).isEqualTo("ステップ1");
            assertThat(result.get(0).getApprovalType()).isEqualTo("ANY");
            assertThat(result.get(1).getName()).isEqualTo("ステップ2");
        }

        @Test
        @DisplayName("null リストは null を返す")
        void null_リストは_null_を返す() {
            assertThat(mapper.toStepResponseList(null)).isNull();
        }
    }

    // ── toFieldResponse ──

    @Nested
    @DisplayName("toFieldResponse")
    class ToFieldResponse {

        @Test
        @DisplayName("テンプレートフィールドエンティティからレスポンスに変換できる")
        void テンプレートフィールドエンティティからレスポンスに変換できる() throws Exception {
            WorkflowTemplateFieldEntity entity = WorkflowTemplateFieldEntity.builder()
                    .templateId(100L)
                    .fieldKey("reason")
                    .fieldLabel("理由")
                    .fieldType(WorkflowFieldType.TEXTAREA)
                    .isRequired(true)
                    .sortOrder(1)
                    .optionsJson(null)
                    .build();
            setId(entity, 300L);

            TemplateFieldResponse result = mapper.toFieldResponse(entity);

            assertThat(result.getId()).isEqualTo(300L);
            assertThat(result.getTemplateId()).isEqualTo(100L);
            assertThat(result.getFieldKey()).isEqualTo("reason");
            assertThat(result.getFieldLabel()).isEqualTo("理由");
            assertThat(result.getFieldType()).isEqualTo("TEXTAREA");
            assertThat(result.getIsRequired()).isTrue();
            assertThat(result.getSortOrder()).isEqualTo(1);
        }

        @Test
        @DisplayName("フィールドリストからレスポンスリストに変換できる")
        void フィールドリストからレスポンスリストに変換できる() throws Exception {
            WorkflowTemplateFieldEntity e1 = WorkflowTemplateFieldEntity.builder()
                    .templateId(100L).fieldKey("f1").fieldLabel("フィールド1")
                    .fieldType(WorkflowFieldType.TEXT).isRequired(false).sortOrder(0).build();
            WorkflowTemplateFieldEntity e2 = WorkflowTemplateFieldEntity.builder()
                    .templateId(100L).fieldKey("f2").fieldLabel("フィールド2")
                    .fieldType(WorkflowFieldType.DATE).isRequired(true).sortOrder(1).build();
            setId(e1, 1L);
            setId(e2, 2L);

            List<TemplateFieldResponse> result = mapper.toFieldResponseList(List.of(e1, e2));

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getFieldType()).isEqualTo("TEXT");
            assertThat(result.get(1).getFieldType()).isEqualTo("DATE");
        }
    }

    // ── toRequestResponse ──

    @Nested
    @DisplayName("toRequestResponse")
    class ToRequestResponse {

        @Test
        @DisplayName("申請エンティティからレスポンスに変換できる")
        void 申請エンティティからレスポンスに変換できる() throws Exception {
            WorkflowRequestEntity entity = WorkflowRequestEntity.builder()
                    .templateId(100L)
                    .scopeType("TEAM")
                    .scopeId(1L)
                    .title("休暇申請")
                    .requestedBy(10L)
                    .fieldValues("{}")
                    .sourceType("MANUAL")
                    .sourceId(null)
                    .build();
            setId(entity, 200L);

            WorkflowRequestResponse result = mapper.toRequestResponse(entity);

            assertThat(result.getId()).isEqualTo(200L);
            assertThat(result.getTemplateId()).isEqualTo(100L);
            assertThat(result.getScopeType()).isEqualTo("TEAM");
            assertThat(result.getTitle()).isEqualTo("休暇申請");
            assertThat(result.getStatus()).isEqualTo("DRAFT");
            assertThat(result.getRequestedBy()).isEqualTo(10L);
            assertThat(result.getFieldValues()).isEqualTo("{}");
            // steps は ignore なので null
            assertThat(result.getSteps()).isNull();
        }
    }

    // ── toRequestStepResponse ──

    @Nested
    @DisplayName("toRequestStepResponse")
    class ToRequestStepResponse {

        @Test
        @DisplayName("申請ステップエンティティからレスポンスに変換できる")
        void 申請ステップエンティティからレスポンスに変換できる() throws Exception {
            WorkflowRequestStepEntity entity = WorkflowRequestStepEntity.builder()
                    .requestId(200L)
                    .stepOrder(1)
                    .build();
            setId(entity, 300L);

            RequestStepResponse result = mapper.toRequestStepResponse(entity);

            assertThat(result.getId()).isEqualTo(300L);
            assertThat(result.getRequestId()).isEqualTo(200L);
            assertThat(result.getStepOrder()).isEqualTo(1);
            assertThat(result.getStatus()).isEqualTo("WAITING");
            // approvers は ignore なので null
            assertThat(result.getApprovers()).isNull();
        }

        @Test
        @DisplayName("申請ステップリストからレスポンスリストに変換できる")
        void 申請ステップリストからレスポンスリストに変換できる() throws Exception {
            WorkflowRequestStepEntity e1 = WorkflowRequestStepEntity.builder()
                    .requestId(200L).stepOrder(1).build();
            WorkflowRequestStepEntity e2 = WorkflowRequestStepEntity.builder()
                    .requestId(200L).stepOrder(2).build();
            setId(e1, 1L);
            setId(e2, 2L);
            e1.startProgress();
            e2.approve();

            List<RequestStepResponse> result = mapper.toRequestStepResponseList(List.of(e1, e2));

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getStatus()).isEqualTo("IN_PROGRESS");
            assertThat(result.get(1).getStatus()).isEqualTo("APPROVED");
        }
    }

    // ── toApproverResponse ──

    @Nested
    @DisplayName("toApproverResponse")
    class ToApproverResponse {

        @Test
        @DisplayName("承認者エンティティからレスポンスに変換できる")
        void 承認者エンティティからレスポンスに変換できる() throws Exception {
            WorkflowRequestApproverEntity entity = WorkflowRequestApproverEntity.builder()
                    .requestStepId(300L)
                    .approverUserId(10L)
                    .build();
            setId(entity, 400L);

            ApproverResponse result = mapper.toApproverResponse(entity);

            assertThat(result.getId()).isEqualTo(400L);
            assertThat(result.getRequestStepId()).isEqualTo(300L);
            assertThat(result.getApproverUserId()).isEqualTo(10L);
            assertThat(result.getDecision()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("承認済み承認者エンティティのdecisionがAPPROVEDになる")
        void 承認済み承認者エンティティのdecisionがAPPROVEDになる() throws Exception {
            WorkflowRequestApproverEntity entity = WorkflowRequestApproverEntity.builder()
                    .requestStepId(300L)
                    .approverUserId(10L)
                    .build();
            setId(entity, 401L);
            entity.approve("承認します", null);

            ApproverResponse result = mapper.toApproverResponse(entity);

            assertThat(result.getDecision()).isEqualTo("APPROVED");
            assertThat(result.getDecisionComment()).isEqualTo("承認します");
        }

        @Test
        @DisplayName("却下済み承認者エンティティのdecisionがREJECTEDになる")
        void 却下済み承認者エンティティのdecisionがREJECTEDになる() throws Exception {
            WorkflowRequestApproverEntity entity = WorkflowRequestApproverEntity.builder()
                    .requestStepId(300L)
                    .approverUserId(10L)
                    .build();
            setId(entity, 402L);
            entity.reject("却下します");

            ApproverResponse result = mapper.toApproverResponse(entity);

            assertThat(result.getDecision()).isEqualTo("REJECTED");
        }

        @Test
        @DisplayName("承認者リストからレスポンスリストに変換できる")
        void 承認者リストからレスポンスリストに変換できる() throws Exception {
            WorkflowRequestApproverEntity e1 = WorkflowRequestApproverEntity.builder()
                    .requestStepId(300L).approverUserId(10L).build();
            WorkflowRequestApproverEntity e2 = WorkflowRequestApproverEntity.builder()
                    .requestStepId(300L).approverUserId(20L).build();
            setId(e1, 1L);
            setId(e2, 2L);

            List<ApproverResponse> result = mapper.toApproverResponseList(List.of(e1, e2));

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getApproverUserId()).isEqualTo(10L);
            assertThat(result.get(1).getApproverUserId()).isEqualTo(20L);
        }
    }

    // ── toCommentResponse ──

    @Nested
    @DisplayName("toCommentResponse")
    class ToCommentResponse {

        @Test
        @DisplayName("コメントエンティティからレスポンスに変換できる")
        void コメントエンティティからレスポンスに変換できる() throws Exception {
            WorkflowRequestCommentEntity entity = WorkflowRequestCommentEntity.builder()
                    .requestId(200L)
                    .userId(10L)
                    .body("コメント本文")
                    .build();
            setId(entity, 500L);

            WorkflowCommentResponse result = mapper.toCommentResponse(entity);

            assertThat(result.getId()).isEqualTo(500L);
            assertThat(result.getRequestId()).isEqualTo(200L);
            assertThat(result.getUserId()).isEqualTo(10L);
            assertThat(result.getBody()).isEqualTo("コメント本文");
        }

        @Test
        @DisplayName("コメントリストからレスポンスリストに変換できる")
        void コメントリストからレスポンスリストに変換できる() throws Exception {
            WorkflowRequestCommentEntity e1 = WorkflowRequestCommentEntity.builder()
                    .requestId(200L).userId(10L).body("コメント1").build();
            WorkflowRequestCommentEntity e2 = WorkflowRequestCommentEntity.builder()
                    .requestId(200L).userId(20L).body("コメント2").build();
            setId(e1, 1L);
            setId(e2, 2L);

            List<WorkflowCommentResponse> result = mapper.toCommentResponseList(List.of(e1, e2));

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getBody()).isEqualTo("コメント1");
        }
    }

    // ── toAttachmentResponse ──

    @Nested
    @DisplayName("toAttachmentResponse")
    class ToAttachmentResponse {

        @Test
        @DisplayName("添付ファイルエンティティからレスポンスに変換できる")
        void 添付ファイルエンティティからレスポンスに変換できる() throws Exception {
            WorkflowRequestAttachmentEntity entity = WorkflowRequestAttachmentEntity.builder()
                    .requestId(200L)
                    .fileKey("files/abc.pdf")
                    .originalFilename("report.pdf")
                    .fileSize(1024L)
                    .uploadedBy(10L)
                    .build();
            setId(entity, 600L);

            WorkflowAttachmentResponse result = mapper.toAttachmentResponse(entity);

            assertThat(result.getId()).isEqualTo(600L);
            assertThat(result.getRequestId()).isEqualTo(200L);
            assertThat(result.getFileKey()).isEqualTo("files/abc.pdf");
            assertThat(result.getOriginalFilename()).isEqualTo("report.pdf");
            assertThat(result.getFileSize()).isEqualTo(1024L);
            assertThat(result.getUploadedBy()).isEqualTo(10L);
        }

        @Test
        @DisplayName("添付ファイルリストからレスポンスリストに変換できる")
        void 添付ファイルリストからレスポンスリストに変換できる() throws Exception {
            WorkflowRequestAttachmentEntity e1 = WorkflowRequestAttachmentEntity.builder()
                    .requestId(200L).fileKey("k1").originalFilename("a.pdf").fileSize(100L).uploadedBy(1L).build();
            WorkflowRequestAttachmentEntity e2 = WorkflowRequestAttachmentEntity.builder()
                    .requestId(200L).fileKey("k2").originalFilename("b.pdf").fileSize(200L).uploadedBy(2L).build();
            setId(e1, 1L);
            setId(e2, 2L);

            List<WorkflowAttachmentResponse> result = mapper.toAttachmentResponseList(List.of(e1, e2));

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getOriginalFilename()).isEqualTo("a.pdf");
            assertThat(result.get(1).getOriginalFilename()).isEqualTo("b.pdf");
        }
    }

    // ── toTemplateDetailResponse (default method) ──

    @Nested
    @DisplayName("toTemplateDetailResponse")
    class ToTemplateDetailResponse {

        @Test
        @DisplayName("テンプレート詳細レスポンスがステップとフィールドを含む")
        void テンプレート詳細レスポンスがステップとフィールドを含む() throws Exception {
            WorkflowTemplateEntity templateEntity = WorkflowTemplateEntity.builder()
                    .scopeType("TEAM").scopeId(1L).name("承認フロー")
                    .isSealRequired(false).isActive(true).sortOrder(0).createdBy(1L).build();
            setId(templateEntity, 100L);

            WorkflowTemplateStepEntity step = WorkflowTemplateStepEntity.builder()
                    .templateId(100L).stepOrder(1).name("ステップ1")
                    .approvalType(ApprovalType.ALL).approverType(ApproverType.USER).build();
            setId(step, 10L);

            WorkflowTemplateFieldEntity field = WorkflowTemplateFieldEntity.builder()
                    .templateId(100L).fieldKey("k").fieldLabel("ラベル")
                    .fieldType(WorkflowFieldType.TEXT).isRequired(false).sortOrder(0).build();
            setId(field, 20L);

            WorkflowTemplateResponse result = mapper.toTemplateDetailResponse(
                    templateEntity, List.of(step), List.of(field));

            assertThat(result.getId()).isEqualTo(100L);
            assertThat(result.getSteps()).hasSize(1);
            assertThat(result.getSteps().get(0).getName()).isEqualTo("ステップ1");
            assertThat(result.getFields()).hasSize(1);
            assertThat(result.getFields().get(0).getFieldLabel()).isEqualTo("ラベル");
        }

        @Test
        @DisplayName("ステップとフィールドが空の場合も正常に変換される")
        void ステップとフィールドが空の場合も正常に変換される() throws Exception {
            WorkflowTemplateEntity templateEntity = WorkflowTemplateEntity.builder()
                    .scopeType("ORGANIZATION").scopeId(2L).name("シンプルフロー")
                    .isSealRequired(false).isActive(true).sortOrder(0).createdBy(2L).build();
            setId(templateEntity, 200L);

            WorkflowTemplateResponse result = mapper.toTemplateDetailResponse(
                    templateEntity, List.of(), List.of());

            assertThat(result.getId()).isEqualTo(200L);
            assertThat(result.getSteps()).isEmpty();
            assertThat(result.getFields()).isEmpty();
        }
    }

    // ── toRequestDetailResponse (default method) ──

    @Nested
    @DisplayName("toRequestDetailResponse")
    class ToRequestDetailResponse {

        @Test
        @DisplayName("申請詳細レスポンスがステップを含む")
        void 申請詳細レスポンスがステップを含む() throws Exception {
            WorkflowRequestEntity requestEntity = WorkflowRequestEntity.builder()
                    .templateId(100L).scopeType("TEAM").scopeId(1L)
                    .title("休暇申請").requestedBy(10L).build();
            setId(requestEntity, 200L);
            requestEntity.submit();

            RequestStepResponse stepResponse = new RequestStepResponse(
                    10L, 200L, 1, "IN_PROGRESS", null, null, List.of());

            WorkflowRequestResponse result = mapper.toRequestDetailResponse(
                    requestEntity, List.of(stepResponse));

            assertThat(result.getId()).isEqualTo(200L);
            assertThat(result.getTitle()).isEqualTo("休暇申請");
            assertThat(result.getStatus()).isEqualTo("PENDING");
            assertThat(result.getSteps()).hasSize(1);
            assertThat(result.getSteps().get(0).getStepOrder()).isEqualTo(1);
        }

        @Test
        @DisplayName("ステップが空の場合も正常に変換される")
        void ステップが空の場合も正常に変換される() throws Exception {
            WorkflowRequestEntity requestEntity = WorkflowRequestEntity.builder()
                    .templateId(100L).scopeType("TEAM").scopeId(1L)
                    .title("下書き申請").requestedBy(10L).build();
            setId(requestEntity, 201L);

            WorkflowRequestResponse result = mapper.toRequestDetailResponse(requestEntity, List.of());

            assertThat(result.getId()).isEqualTo(201L);
            assertThat(result.getStatus()).isEqualTo("DRAFT");
            assertThat(result.getSteps()).isEmpty();
        }
    }
}
