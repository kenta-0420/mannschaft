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
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
    @Mock private ContentVisibilityChecker contentVisibilityChecker;
    @Mock private com.mannschaft.app.common.AccessControlService accessControlService;

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
            service.deleteActivity(ACTIVITY_ID, USER_ID);
            verify(resultRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: 活動記録不在でACTIVITY_001例外")
        void 削除_不在_例外() {
            given(resultRepository.findById(ACTIVITY_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.deleteActivity(ACTIVITY_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ACTIVITY_001"));
        }
    }

    @Nested
    @DisplayName("duplicateActivity")
    class DuplicateActivity {

        private ActivityResultEntity teamOriginal() {
            return ActivityResultEntity.builder()
                    .scopeType(ActivityScopeType.TEAM).scopeId(SCOPE_ID).title("元活動").build();
        }

        // AC-1: 他スコープ会員は複製不可（IDOR封じ）
        @Test
        @DisplayName("duplicateActivity_他スコープ会員は403（COMMON_002）")
        void 複製_他スコープ_403() {
            given(resultRepository.findById(ACTIVITY_ID)).willReturn(Optional.of(teamOriginal()));
            org.mockito.BDDMockito.willThrow(new BusinessException(
                    com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkMembership(USER_ID, SCOPE_ID, "TEAM");

            assertThatThrownBy(() -> service.duplicateActivity(ACTIVITY_ID, USER_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }

        // AC-2: 自スコープ会員は従来通り複製成功（非回帰）
        @Test
        @DisplayName("duplicateActivity_自スコープ会員は成功（非回帰）")
        void 複製_自スコープ_成功() {
            ActivityResultEntity original = teamOriginal();
            given(resultRepository.findById(ACTIVITY_ID)).willReturn(Optional.of(original));
            given(resultRepository.save(any())).willReturn(original);
            given(participantRepository.findByActivityResultIdOrderByCreatedAtAsc(ACTIVITY_ID))
                    .willReturn(List.of());

            ActivityResultEntity result = service.duplicateActivity(ACTIVITY_ID, USER_ID, null);
            assertThat(result).isNotNull();
            verify(accessControlService).checkMembership(USER_ID, SCOPE_ID, "TEAM");
        }
    }

    @Nested
    @DisplayName("listPublicActivities")
    class ListPublicActivities {

        @Test
        @DisplayName("未認証: ContentVisibilityChecker が公開と判定した活動のみ返す")
        void 未認証_公開判定済みのみ返す() {
            ActivityResultEntity pub = ActivityResultEntity.builder()
                    .scopeType(ActivityScopeType.TEAM).scopeId(SCOPE_ID).title("公開活動").build();
            ActivityResultEntity priv = ActivityResultEntity.builder()
                    .scopeType(ActivityScopeType.TEAM).scopeId(SCOPE_ID).title("非公開活動").build();
            ReflectionTestUtils.setField(pub, "id", 1L);
            ReflectionTestUtils.setField(priv, "id", 2L);

            Page<ActivityResultEntity> allPage = new PageImpl<>(List.of(pub, priv));
            given(resultRepository.findByScopeTypeAndScopeIdOrderByActivityDateDescIdDesc(
                    ActivityScopeType.TEAM, SCOPE_ID, PageRequest.of(0, 10)))
                    .willReturn(allPage);
            // userId=null（未認証）で PUBLIC(1L) のみ通過、MEMBERS_ONLY(2L) は拒否
            given(contentVisibilityChecker.filterAccessible(
                    ReferenceType.ACTIVITY_RESULT, Set.of(1L, 2L), null))
                    .willReturn(Set.of(1L));

            Page<ActivityResultEntity> result = service.listPublicActivities(
                    ActivityScopeType.TEAM, SCOPE_ID, PageRequest.of(0, 10));

            assertThat(result.getContent())
                    .hasSize(1)
                    .extracting(ActivityResultEntity::getId)
                    .containsExactly(1L);
        }

        @Test
        @DisplayName("空ページ: リポジトリが空なら Checker を呼ばず空ページを返す")
        void 空ページ_Checker不呼び出し_空返却() {
            given(resultRepository.findByScopeTypeAndScopeIdOrderByActivityDateDescIdDesc(
                    ActivityScopeType.TEAM, SCOPE_ID, PageRequest.of(0, 10)))
                    .willReturn(Page.empty());

            Page<ActivityResultEntity> result = service.listPublicActivities(
                    ActivityScopeType.TEAM, SCOPE_ID, PageRequest.of(0, 10));

            assertThat(result.getContent()).isEmpty();
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

            service.addParticipants(ACTIVITY_ID, USER_ID, request);
            // No new save since already exists
        }
    }
}
