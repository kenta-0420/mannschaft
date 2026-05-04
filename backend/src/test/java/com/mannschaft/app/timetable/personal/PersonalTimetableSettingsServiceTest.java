package com.mannschaft.app.timetable.personal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timetable.personal.dto.UpdatePersonalTimetableSettingsRequest;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSettingsEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSettingsRepository;
import com.mannschaft.app.timetable.personal.service.PersonalTimetableSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * F03.15 Phase 3 個人時間割ユーザー設定サービスのユニットテスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalTimetableSettingsService ユニットテスト")
class PersonalTimetableSettingsServiceTest {

    private static final Long USER_ID = 100L;

    @Mock private PersonalTimetableSettingsRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private PersonalTimetableSettingsService service;

    @BeforeEach
    void setUp() {
        service = new PersonalTimetableSettingsService(repository, objectMapper);
    }

    @Test
    @DisplayName("getOrCreate: 未存在ならデフォルトで INSERT")
    void getOrCreate_デフォルト作成() {
        given(repository.findById(USER_ID)).willReturn(Optional.empty());
        given(repository.save(any(PersonalTimetableSettingsEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        var entity = service.getOrCreate(USER_ID);
        assertThat(entity.getUserId()).isEqualTo(USER_ID);
        assertThat(entity.getAutoReflectClassChangesToCalendar()).isTrue();
        assertThat(entity.getNotifyTeamSlotNoteUpdates()).isTrue();
    }

    @Test
    @DisplayName("update: 部分更新（visible_default_fields は不正キーを除外）")
    void update_部分更新() {
        PersonalTimetableSettingsEntity entity = PersonalTimetableSettingsEntity.builder()
                .userId(USER_ID).build();
        given(repository.findById(USER_ID)).willReturn(Optional.of(entity));
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));
        var req = new UpdatePersonalTimetableSettingsRequest(
                false, null, "UNIV_90MIN",
                List.of("preparation", "review", "BOGUS"));
        var updated = service.update(USER_ID, req);
        assertThat(updated.getAutoReflectClassChangesToCalendar()).isFalse();
        assertThat(updated.getDefaultPeriodTemplate())
                .isEqualTo(PersonalTimetableSettingsEntity.DefaultPeriodTemplate.UNIV_90MIN);
        // BOGUS が除外されることを確認
        assertThat(updated.getVisibleDefaultFields()).doesNotContain("BOGUS");
        assertThat(updated.getVisibleDefaultFields()).contains("preparation").contains("review");
    }

    @Test
    @DisplayName("update: 不正なテンプレート名で 400")
    void update_不正テンプレート() {
        PersonalTimetableSettingsEntity entity = PersonalTimetableSettingsEntity.builder()
                .userId(USER_ID).build();
        given(repository.findById(USER_ID)).willReturn(Optional.of(entity));
        var req = new UpdatePersonalTimetableSettingsRequest(null, null, "INVALID", null);
        assertThatThrownBy(() -> service.update(USER_ID, req))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("parseVisibleDefaultFields: パース失敗時はデフォルト4項目")
    void parseVisible_デフォルト() {
        PersonalTimetableSettingsEntity entity = PersonalTimetableSettingsEntity.builder()
                .userId(USER_ID).visibleDefaultFields("not-json")
                .build();
        var list = service.parseVisibleDefaultFields(entity);
        assertThat(list).containsExactly("preparation", "review", "items_to_bring", "free_memo");
    }
}
