package com.mannschaft.app.forms.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * フォームテンプレートレスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class FormTemplateResponse {

    Long id;
    String status;
    FormScopeDto scope;
    FormContentDto content;
    FormWorkflowDto workflow;
    FormEditPolicyDto editPolicy;
    FormStatsDto stats;
    FormTimelineDto timeline;
    FormAuditDto audit;
    List<FormFieldResponse> fields;

    public record FormScopeDto(String scopeType, Long scopeId) {}

    public record FormContentDto(String name, String description, String icon, String color, Integer sortOrder) {}

    public record FormWorkflowDto(Boolean requiresApproval, Long workflowTemplateId, Boolean isSealOnPdf) {}

    public record FormEditPolicyDto(Boolean allowEditAfterSubmit, Boolean autoFillEnabled,
                                    Integer maxSubmissionsPerUser) {}

    public record FormStatsDto(Integer submissionCount, Integer targetCount, Long presetId) {}

    public record FormTimelineDto(LocalDateTime deadline, LocalDateTime publishedAt, LocalDateTime closedAt) {}

    public record FormAuditDto(Long version, Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
