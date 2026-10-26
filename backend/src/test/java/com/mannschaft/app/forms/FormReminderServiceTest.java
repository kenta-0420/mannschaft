package com.mannschaft.app.forms;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.forms.dto.FormRemindResponse;
import com.mannschaft.app.forms.entity.FormTemplateEntity;
import com.mannschaft.app.forms.event.FormTemplateRemindEvent;
import com.mannschaft.app.forms.repository.FormSubmissionRepository;
import com.mannschaft.app.forms.repository.FormTemplateRepository;
import com.mannschaft.app.forms.service.FormReminderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link FormReminderService} の単体テスト（F05.7 Phase 11 第四陣 4-B）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FormReminderService 単体テスト")
class FormReminderServiceTest {

    @Mock private FormTemplateRepository templateRepository;
    @Mock private FormSubmissionRepository submissionRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AccessControlService accessControlService;

    @InjectMocks
    private FormReminderService reminderService;

    private FormTemplateEntity publishedTemplate() {
        FormTemplateEntity t = FormTemplateEntity.builder()
                .scopeType("teams").scopeId(7L)
                .name("年次更新").createdBy(1L).build();
        t.publish();
        return t;
    }

    @Test
    @DisplayName("全未提出者リマインドは候補から既提出者を除外する")
    void remindAll_ExcludesSubmittedUsers() {
        given(templateRepository.findByIdAndScopeTypeAndScopeId(anyLong(), anyString(), anyLong()))
                .willReturn(Optional.of(publishedTemplate()));
        given(submissionRepository.findSubmittedUserIds(anyLong(), any()))
                .willReturn(List.of(11L, 12L));

        FormRemindResponse response = reminderService.remindAllUnsubmitted(
                "teams", 7L, 100L, List.of(10L, 11L, 12L, 13L), 1L);

        assertThat(response.getRemindedCount()).isEqualTo(2);
        ArgumentCaptor<FormTemplateRemindEvent> captor = ArgumentCaptor.forClass(FormTemplateRemindEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().targetUserIds()).containsExactly(10L, 13L);
        assertThat(captor.getValue().remindKind()).isEqualTo(FormTemplateRemindEvent.RemindKind.ALL_UNSUBMITTED);
    }

    @Test
    @DisplayName("候補ユーザーが空の場合は何もせず 0 を返す")
    void remindAll_EmptyCandidates() {
        given(templateRepository.findByIdAndScopeTypeAndScopeId(anyLong(), anyString(), anyLong()))
                .willReturn(Optional.of(publishedTemplate()));

        FormRemindResponse response = reminderService.remindAllUnsubmitted(
                "teams", 7L, 100L, List.of(), 1L);

        assertThat(response.getRemindedCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("DRAFT 状態のテンプレートはリマインド不可")
    void remindAll_DraftTemplate_Throws() {
        FormTemplateEntity draft = FormTemplateEntity.builder()
                .scopeType("teams").scopeId(7L).name("下書き").createdBy(1L).build();
        given(templateRepository.findByIdAndScopeTypeAndScopeId(anyLong(), anyString(), anyLong()))
                .willReturn(Optional.of(draft));

        assertThatThrownBy(() -> reminderService.remindAllUnsubmitted(
                "teams", 7L, 100L, List.of(10L), 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("特定者向けリマインドは指定ユーザー全員にイベント発火")
    void remindSpecific_Success() {
        given(templateRepository.findByIdAndScopeTypeAndScopeId(anyLong(), anyString(), anyLong()))
                .willReturn(Optional.of(publishedTemplate()));

        FormRemindResponse response = reminderService.remindSpecificUsers(
                "teams", 7L, 100L, List.of(10L, 20L, 30L), "至急ご対応ください", 1L);

        assertThat(response.getRemindedCount()).isEqualTo(3);
        ArgumentCaptor<FormTemplateRemindEvent> captor = ArgumentCaptor.forClass(FormTemplateRemindEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().remindKind()).isEqualTo(FormTemplateRemindEvent.RemindKind.SPECIFIC_USERS);
        assertThat(captor.getValue().customMessage()).isEqualTo("至急ご対応ください");
    }

    @Test
    @DisplayName("特定者向けで空のユーザーリストは REMIND_NO_TARGET")
    void remindSpecific_EmptyUsers_Throws() {
        given(templateRepository.findByIdAndScopeTypeAndScopeId(anyLong(), anyString(), anyLong()))
                .willReturn(Optional.of(publishedTemplate()));

        assertThatThrownBy(() -> reminderService.remindSpecificUsers(
                "teams", 7L, 100L, List.of(), null, 1L))
                .isInstanceOf(BusinessException.class);
    }
}
