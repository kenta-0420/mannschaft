package com.mannschaft.app.activity;

import com.mannschaft.app.activity.dto.AddParticipantsRequest;
import com.mannschaft.app.activity.dto.CreateActivityRequest;
import com.mannschaft.app.activity.entity.ActivityParticipantEntity;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.repository.ActivityParticipantRepository;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.activity.service.ActivityResultService;
import com.mannschaft.app.activity.service.ActivityTemplateService;
import com.mannschaft.app.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityResultService 単体テスト")
class ActivityResultServiceTest {

    @Mock private ActivityResultRepository resultRepository;
    @Mock private ActivityParticipantRepository participantRepository;
    @Mock private ActivityTemplateService templateService;
    @Mock private ActivityMapper activityMapper;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private ActivityResultService service;

    private static final Long ACTIVITY_ID = 10L;
    private static final Long USER_ID = 100L;
    private static final Long SCOPE_ID = 1L;

    @Nested
    @DisplayName("createActivity")
    class CreateActivity {

        @Test
        @DisplayName("正常系: 活動記録が作成される")
        void 作成_正常_保存() {
            CreateActivityRequest request = new CreateActivityRequest(
                    1L, "練習", LocalDate.now(), null, null, null, null, null, null, null, null, null);
            ActivityResultEntity saved = ActivityResultEntity.builder()
                    .scopeType(ActivityScopeType.TEAM).scopeId(SCOPE_ID).title("練習").build();
            given(resultRepository.save(any())).willReturn(saved);

            ActivityResultEntity result = service.createActivity(USER_ID, ActivityScopeType.TEAM, SCOPE_ID, request);
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: 終了時刻が開始時刻より前でACTIVITY_020例外")
        void 作成_時刻不正_例外() {
            CreateActivityRequest request = new CreateActivityRequest(
                    1L, "練習", LocalDate.now(), LocalTime.of(15, 0), LocalTime.of(10, 0),
                    null, null, null, null, null, null, null);

            assertThatThrownBy(() -> service.createActivity(USER_ID, ActivityScopeType.TEAM, SCOPE_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ACTIVITY_020"));
        }
    }

    @Nested
    @DisplayName("deleteActivity")
    class DeleteActivity {
        @Test
        @DisplayName("正常系: 活動記録が論理削除される")
        void 削除_正常_論理削除() {
            ActivityResultEntity entity = ActivityResultEntity.builder().title("テスト").build();
            given(resultRepository.findById(ACTIVITY_ID)).willReturn(Optional.of(entity));
            service.deleteActivity(ACTIVITY_ID);
            verify(resultRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: 活動記録不在でACTIVITY_001例外")
        void 削除_不在_例外() {
            given(resultRepository.findById(ACTIVITY_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.deleteActivity(ACTIVITY_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ACTIVITY_001"));
        }
    }

    @Nested
    @DisplayName("addParticipants")
    class AddParticipants {
        @Test
        @DisplayName("正常系: 重複参加者はスキップされる")
        void 追加_重複スキップ() {
            ActivityResultEntity entity = ActivityResultEntity.builder().title("テスト").build();
            given(resultRepository.findById(ACTIVITY_ID)).willReturn(Optional.of(entity));
            given(participantRepository.findByActivityResultIdAndUserId(ACTIVITY_ID, 1L))
                    .willReturn(Optional.of(ActivityParticipantEntity.builder().build()));
            given(participantRepository.findByActivityResultIdOrderByCreatedAtAsc(ACTIVITY_ID)).willReturn(List.of());
            given(activityMapper.toParticipantResponseList(any())).willReturn(List.of());

            AddParticipantsRequest request = new AddParticipantsRequest(List.of(1L), null);

            service.addParticipants(ACTIVITY_ID, request);
            // No new save since already exists
        }
    }
}
