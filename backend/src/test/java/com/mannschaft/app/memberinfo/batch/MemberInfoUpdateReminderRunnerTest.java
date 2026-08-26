package com.mannschaft.app.memberinfo.batch;

import com.mannschaft.app.memberinfo.MemberInfoFieldType;
import com.mannschaft.app.memberinfo.TeamMemberInfoFieldEntity;
import com.mannschaft.app.memberinfo.TeamMemberInfoFieldRepository;
import com.mannschaft.app.memberinfo.TeamMemberInfoResponseEntity;
import com.mannschaft.app.memberinfo.TeamMemberInfoResponseRepository;
import com.mannschaft.app.memberinfo.event.MemberInfoUpdateReminderNotificationEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link MemberInfoUpdateReminderRunner} の単体テスト（Issue #2834 / CMP-056 第2群ロット2）。
 *
 * <p>検証する受け入れ条件:</p>
 * <ul>
 *   <li><b>AC-3</b>: 通知は publish するだけで {@code NotificationService} を直接呼ばない
 *       （＝この TX がロールバックすれば {@code AFTER_COMMIT} は発火せず通知は作られない）。</li>
 *   <li><b>AC-4</b>: 独立TX内でフィールド・回答を読み直して期限切れ／24時間クールダウンを
 *       再判定し、再実行しても二重送信しない。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemberInfoUpdateReminderRunner 単体テスト（Issue #2834 / CMP-056）")
class MemberInfoUpdateReminderRunnerTest {

    private static final Long TEAM_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final Long FIELD_ID = 100L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 9, 0);

    @Mock
    private TeamMemberInfoFieldRepository fieldRepository;

    @Mock
    private TeamMemberInfoResponseRepository responseRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private MemberInfoUpdateReminderRunner runner;

    @Test
    @DisplayName("AC-3: 未回答なら last_reminder_sent_at を記録し、通知配送要求（ID のみ）を publish する")
    void markReminderSent_missingResponse_recordsAndPublishes() {
        given(fieldRepository.findAllById(List.of(FIELD_ID))).willReturn(List.of(buildField(FIELD_ID, 6)));
        given(responseRepository.findByTeamIdAndUserId(TEAM_ID, USER_ID)).willReturn(List.of());

        boolean published = runner.markReminderSent(TEAM_ID, USER_ID, List.of(FIELD_ID), NOW);

        assertThat(published).isTrue();
        verify(responseRepository).save(any());
        ArgumentCaptor<MemberInfoUpdateReminderNotificationEvent> captor =
                ArgumentCaptor.forClass(MemberInfoUpdateReminderNotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().teamId()).isEqualTo(TEAM_ID);
        assertThat(captor.getValue().recipientUserId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().fieldId()).isEqualTo(FIELD_ID);
    }

    @Test
    @DisplayName("AC-4: 24時間以内に送信済みなら記録も通知もしない")
    void markReminderSent_withinCooldown_isIdempotent() {
        given(fieldRepository.findAllById(List.of(FIELD_ID))).willReturn(List.of(buildField(FIELD_ID, 6)));
        given(responseRepository.findByTeamIdAndUserId(TEAM_ID, USER_ID))
                .willReturn(List.of(buildResponse(FIELD_ID, null, NOW.minusHours(1))));

        boolean published = runner.markReminderSent(TEAM_ID, USER_ID, List.of(FIELD_ID), NOW);

        assertThat(published).isFalse();
        verify(responseRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(MemberInfoUpdateReminderNotificationEvent.class));
    }

    @Test
    @DisplayName("期限内に確認済みなら記録も通知もしない")
    void markReminderSent_confirmedRecently_doesNothing() {
        given(fieldRepository.findAllById(List.of(FIELD_ID))).willReturn(List.of(buildField(FIELD_ID, 6)));
        given(responseRepository.findByTeamIdAndUserId(TEAM_ID, USER_ID))
                .willReturn(List.of(buildResponse(FIELD_ID, NOW.minusMonths(1), null)));

        boolean published = runner.markReminderSent(TEAM_ID, USER_ID, List.of(FIELD_ID), NOW);

        assertThat(published).isFalse();
        verify(responseRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(MemberInfoUpdateReminderNotificationEvent.class));
    }

    @Test
    @DisplayName("読み直した時点でフィールドが無効化されていれば何もしない")
    void markReminderSent_fieldDeactivated_doesNothing() {
        TeamMemberInfoFieldEntity field = buildField(FIELD_ID, 6);
        field.deactivate();
        given(fieldRepository.findAllById(List.of(FIELD_ID))).willReturn(List.of(field));

        boolean published = runner.markReminderSent(TEAM_ID, USER_ID, List.of(FIELD_ID), NOW);

        assertThat(published).isFalse();
        verify(eventPublisher, never()).publishEvent(any(MemberInfoUpdateReminderNotificationEvent.class));
    }

    private TeamMemberInfoFieldEntity buildField(Long id, Integer intervalMonths) {
        TeamMemberInfoFieldEntity entity = TeamMemberInfoFieldEntity.builder()
                .teamId(TEAM_ID)
                .fieldName("緊急連絡先")
                .fieldType(MemberInfoFieldType.TEXT)
                .isRequired(false)
                .isSensitive(false)
                .refreshIntervalMonths(intervalMonths)
                .sortOrder(0)
                .build();
        setId(entity, id);
        return entity;
    }

    private TeamMemberInfoResponseEntity buildResponse(Long fieldId, LocalDateTime confirmedAt,
                                                       LocalDateTime lastReminderSentAt) {
        return TeamMemberInfoResponseEntity.builder()
                .teamId(TEAM_ID)
                .userId(USER_ID)
                .fieldId(fieldId)
                .confirmedAt(confirmedAt)
                .lastReminderSentAt(lastReminderSentAt)
                .build();
    }

    private void setId(Object entity, Long id) {
        Class<?> clazz = entity.getClass();
        while (clazz != null) {
            try {
                java.lang.reflect.Field f = clazz.getDeclaredField("id");
                f.setAccessible(true);
                f.set(entity, id);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("id フィールドが見つかりません");
    }
}
