package com.mannschaft.app.reflection.service;

import com.mannschaft.app.reflection.dto.ReflectionSettingsResponse;
import com.mannschaft.app.reflection.dto.UpdateReflectionSettingsRequest;
import com.mannschaft.app.reflection.entity.UserReflectionSettingsEntity;
import com.mannschaft.app.reflection.repository.UserReflectionSettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ReflectionSettingsService} 単体テスト（F06.5・§2.7 / §7 #14〜#15）。
 *
 * <p>カバー AC-23: 想起通知時刻のユーザー設定（既定 8 時・UPSERT）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReflectionSettingsService 単体テスト")
class ReflectionSettingsServiceTest {

    @Mock private UserReflectionSettingsRepository repository;

    @InjectMocks private ReflectionSettingsService service;

    private static final Long USER_ID = 100L;

    @Test
    @DisplayName("AC-23: 未設定ユーザーは既定 8 時を返す")
    void getSettings_noRecord_default8() {
        given(repository.findById(USER_ID)).willReturn(Optional.empty());

        ReflectionSettingsResponse resp = service.getSettings(USER_ID);

        assertThat(resp.remindHour()).isEqualTo(8);
    }

    @Test
    @DisplayName("AC-23: 設定済みユーザーは保存値を返す")
    void getSettings_record_returnsStored() {
        given(repository.findById(USER_ID)).willReturn(Optional.of(
                UserReflectionSettingsEntity.builder().userId(USER_ID).remindHour(21).build()));

        ReflectionSettingsResponse resp = service.getSettings(USER_ID);

        assertThat(resp.remindHour()).isEqualTo(21);
    }

    @Test
    @DisplayName("AC-23: 初回 PUT で INSERT（remind_hour 永続化）")
    void updateSettings_firstTime_insert() {
        given(repository.findById(USER_ID)).willReturn(Optional.empty());
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        ReflectionSettingsResponse resp = service.updateSettings(USER_ID,
                new UpdateReflectionSettingsRequest(22));

        assertThat(resp.remindHour()).isEqualTo(22);
        ArgumentCaptor<UserReflectionSettingsEntity> captor =
                ArgumentCaptor.forClass(UserReflectionSettingsEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getRemindHour()).isEqualTo(22);
    }

    @Test
    @DisplayName("AC-23: 2 回目 PUT で UPDATE（既存レコードのミューテート）")
    void updateSettings_secondTime_update() {
        UserReflectionSettingsEntity existing = UserReflectionSettingsEntity.builder()
                .userId(USER_ID).remindHour(8).build();
        given(repository.findById(USER_ID)).willReturn(Optional.of(existing));
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        ReflectionSettingsResponse resp = service.updateSettings(USER_ID,
                new UpdateReflectionSettingsRequest(6));

        assertThat(resp.remindHour()).isEqualTo(6);
        assertThat(existing.getRemindHour()).isEqualTo(6);
    }

    @Test
    @DisplayName("remindHour: remind_at 生成用に時刻を解決（未設定は 8）")
    void remindHour_resolve() {
        given(repository.findById(USER_ID)).willReturn(Optional.empty());
        assertThat(service.remindHour(USER_ID)).isEqualTo(8);
    }
}
