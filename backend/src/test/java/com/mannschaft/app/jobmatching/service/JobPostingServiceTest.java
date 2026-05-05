package com.mannschaft.app.jobmatching.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
import com.mannschaft.app.jobmatching.enums.RewardType;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import com.mannschaft.app.jobmatching.enums.WorkLocationType;
import com.mannschaft.app.jobmatching.exception.JobmatchingErrorCode;
import com.mannschaft.app.jobmatching.policy.JobPolicy;
import com.mannschaft.app.jobmatching.repository.JobApplicationRepository;
import com.mannschaft.app.jobmatching.repository.JobPostingRepository;
import com.mannschaft.app.jobmatching.service.command.CreateJobPostingCommand;
import com.mannschaft.app.jobmatching.service.command.UpdateJobPostingCommand;
import com.mannschaft.app.jobmatching.state.JobPostingStateMachine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * {@link JobPostingService} のユニットテスト。
 *
 * <p>F13.1 MVP の業務ルール（MVP 公開範囲制約・報酬上下限・日時整合性）を中心に検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("JobPostingService 単体テスト")
class JobPostingServiceTest {

    @Mock private JobPostingRepository postingRepository;
    @Mock private JobApplicationRepository applicationRepository;
    @Mock private JobPostingStateMachine stateMachine;
    @Mock private JobPolicy jobPolicy;
    /** F00 Phase C 試験的置換 — listByTeamForViewer から呼ばれるが、本テスト群では未使用。 */
    @Mock private ContentVisibilityChecker visibilityChecker;

    @InjectMocks private JobPostingService service;

    private static final Long USER_ID = 100L;
    private static final Long TEAM_ID = 10L;
    private static final Long POSTING_ID = 1000L;

    // ---------------------------------------------------------------------
    // create
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("create")
    class CreateTest {

        @Test
        @DisplayName("正常系: DRAFT 状態で保存される")
        void 正常_DRAFT保存() {
            CreateJobPostingCommand cmd = defaultCreateCommand(
                    VisibilityScope.TEAM_MEMBERS, 3000);
            given(jobPolicy.canCreatePosting(USER_ID, TEAM_ID)).willReturn(true);
            given(postingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            JobPostingEntity result = service.create(cmd, USER_ID);

            assertThat(result.getStatus()).isEqualTo(JobPostingStatus.DRAFT);
            assertThat(result.getCreatedByUserId()).isEqualTo(USER_ID);
            assertThat(result.getTeamId()).isEqualTo(TEAM_ID);
        }

        @Test
        @DisplayName("異常系: 権限なしで JOB_PERMISSION_DENIED")
        void 権限なし_拒否() {
            CreateJobPostingCommand cmd = defaultCreateCommand(VisibilityScope.TEAM_MEMBERS, 3000);
            given(jobPolicy.canCreatePosting(USER_ID, TEAM_ID)).willReturn(false);

            assertThatThrownBy(() -> service.create(cmd, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_PERMISSION_DENIED));
        }

        @Test
        @DisplayName("異常系: MVP 未対応の公開範囲 (JOBBER_INTERNAL) で JOB_VIS_NOT_SUPPORTED")
        void MVP未対応スコープ_拒否() {
            CreateJobPostingCommand cmd = defaultCreateCommand(VisibilityScope.JOBBER_INTERNAL, 3000);
            given(jobPolicy.canCreatePosting(USER_ID, TEAM_ID)).willReturn(true);

            assertThatThrownBy(() -> service.create(cmd, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_VIS_NOT_SUPPORTED));
        }

        @Test
        @DisplayName("異常系: 公開範囲 CUSTOM_TEMPLATE も MVP 未対応で JOB_VIS_NOT_SUPPORTED")
        void MVP未対応_カスタムテンプレ_拒否() {
            CreateJobPostingCommand cmd = defaultCreateCommand(VisibilityScope.CUSTOM_TEMPLATE, 3000);
            given(jobPolicy.canCreatePosting(USER_ID, TEAM_ID)).willReturn(true);

            assertThatThrownBy(() -> service.create(cmd, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_VIS_NOT_SUPPORTED));
        }

        @Test
        @DisplayName("正常系: 公開範囲 TEAM_MEMBERS_SUPPORTERS は MVP で許容")
        void 公開範囲_サポーター含む_ok() {
            CreateJobPostingCommand cmd = defaultCreateCommand(
                    VisibilityScope.TEAM_MEMBERS_SUPPORTERS, 3000);
            given(jobPolicy.canCreatePosting(USER_ID, TEAM_ID)).willReturn(true);
            given(postingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            JobPostingEntity result = service.create(cmd, USER_ID);
            assertThat(result.getVisibilityScope()).isEqualTo(VisibilityScope.TEAM_MEMBERS_SUPPORTERS);
        }

        @Test
        @DisplayName("異常系: 報酬額が下限 500 未満で JOB_REWARD_OUT_OF_RANGE")
        void 報酬_下限未満_拒否() {
            CreateJobPostingCommand cmd = defaultCreateCommand(VisibilityScope.TEAM_MEMBERS, 499);
            given(jobPolicy.canCreatePosting(USER_ID, TEAM_ID)).willReturn(true);

            assertThatThrownBy(() -> service.create(cmd, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_REWARD_OUT_OF_RANGE));
        }

        @Test
        @DisplayName("異常系: 報酬額が上限 1,000,000 超過で JOB_REWARD_OUT_OF_RANGE")
        void 報酬_上限超過_拒否() {
            CreateJobPostingCommand cmd = defaultCreateCommand(
                    VisibilityScope.TEAM_MEMBERS, 1_000_001);
            given(jobPolicy.canCreatePosting(USER_ID, TEAM_ID)).willReturn(true);

            assertThatThrownBy(() -> service.create(cmd, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_REWARD_OUT_OF_RANGE));
        }

        @Test
        @DisplayName("境界: 報酬額ちょうど下限 500 と上限 1,000,000 は許容")
        void 報酬_境界値_ok() {
            given(jobPolicy.canCreatePosting(USER_ID, TEAM_ID)).willReturn(true);
            given(postingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.create(defaultCreateCommand(VisibilityScope.TEAM_MEMBERS, 500), USER_ID);
            service.create(defaultCreateCommand(VisibilityScope.TEAM_MEMBERS, 1_000_000), USER_ID);
        }

        @Test
        @DisplayName("異常系: 応募締切が業務開始より後の場合は JOB_DEADLINE_PASSED")
        void 応募締切_業務開始後_拒否() {
            LocalDateTime now = LocalDateTime.now();
            CreateJobPostingCommand cmd = new CreateJobPostingCommand(
                    TEAM_ID, "テスト", "説明", "cat",
                    WorkLocationType.ONSITE, "東京",
                    now.plusDays(1),  // workStartAt
                    now.plusDays(1).plusHours(3),  // workEndAt
                    RewardType.LUMP_SUM, 3000, 1,
                    now.plusDays(2),  // applicationDeadlineAt（業務開始より後）
                    VisibilityScope.TEAM_MEMBERS, null);
            given(jobPolicy.canCreatePosting(USER_ID, TEAM_ID)).willReturn(true);

            assertThatThrownBy(() -> service.create(cmd, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_DEADLINE_PASSED));
        }

        @Test
        @DisplayName("異常系: 業務終了が業務開始以前で JOB_INVALID_STATE_TRANSITION")
        void 業務終了_開始以前_拒否() {
            LocalDateTime now = LocalDateTime.now();
            CreateJobPostingCommand cmd = new CreateJobPostingCommand(
                    TEAM_ID, "テスト", "説明", "cat",
                    WorkLocationType.ONSITE, "東京",
                    now.plusDays(1),  // workStartAt
                    now.plusDays(1),  // workEndAt と同じ（isAfter ではない）
                    RewardType.LUMP_SUM, 3000, 1,
                    now.plusHours(12),
                    VisibilityScope.TEAM_MEMBERS, null);
            given(jobPolicy.canCreatePosting(USER_ID, TEAM_ID)).willReturn(true);

            assertThatThrownBy(() -> service.create(cmd, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_INVALID_STATE_TRANSITION));
        }

        @Test
        @DisplayName("異常系: publishAt が過去日時で JOB_INVALID_STATE_TRANSITION")
        void publishAt_過去_拒否() {
            LocalDateTime now = LocalDateTime.now();
            CreateJobPostingCommand cmd = new CreateJobPostingCommand(
                    TEAM_ID, "テスト", "説明", "cat",
                    WorkLocationType.ONSITE, "東京",
                    now.plusDays(1), now.plusDays(1).plusHours(3),
                    RewardType.LUMP_SUM, 3000, 1,
                    now.plusHours(12),
                    VisibilityScope.TEAM_MEMBERS,
                    now.minusHours(1));  // publishAt 過去
            given(jobPolicy.canCreatePosting(USER_ID, TEAM_ID)).willReturn(true);

            assertThatThrownBy(() -> service.create(cmd, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ---------------------------------------------------------------------
    // publish
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("publish")
    class PublishTest {

        @Test
        @DisplayName("正常系: DRAFT → OPEN")
        void 正常_DRAFT_OPEN() {
            JobPostingEntity posting = postingWithStatus(JobPostingStatus.DRAFT);
            given(postingRepository.findById(POSTING_ID)).willReturn(Optional.of(posting));
            given(jobPolicy.canEditPosting(USER_ID, posting)).willReturn(true);
            given(postingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            JobPostingEntity saved = service.publish(POSTING_ID, USER_ID);

            assertThat(saved.getStatus()).isEqualTo(JobPostingStatus.OPEN);
        }

        @Test
        @DisplayName("異常系: 権限なしで JOB_PERMISSION_DENIED")
        void 権限なし_拒否() {
            JobPostingEntity posting = postingWithStatus(JobPostingStatus.DRAFT);
            given(postingRepository.findById(POSTING_ID)).willReturn(Optional.of(posting));
            given(jobPolicy.canEditPosting(USER_ID, posting)).willReturn(false);

            assertThatThrownBy(() -> service.publish(POSTING_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_PERMISSION_DENIED));
        }

        @Test
        @DisplayName("異常系: 求人不在で JOB_NOT_FOUND")
        void 求人不在_拒否() {
            given(postingRepository.findById(POSTING_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.publish(POSTING_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_NOT_FOUND));
        }
    }

    // ---------------------------------------------------------------------
    // update
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("update")
    class UpdateTest {

        @Test
        @DisplayName("正常系: 応募者ゼロ件の求人ではタイトル・報酬も変更可能")
        void 正常_応募なし_全属性変更可() {
            JobPostingEntity posting = postingWithStatus(JobPostingStatus.OPEN);
            given(postingRepository.findById(POSTING_ID)).willReturn(Optional.of(posting));
            given(jobPolicy.canEditPosting(USER_ID, posting)).willReturn(true);
            given(applicationRepository.countByJobPostingId(POSTING_ID)).willReturn(0);
            given(postingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            UpdateJobPostingCommand cmd = new UpdateJobPostingCommand(
                    "新タイトル", null, null, null, null, null, null, null, 5000,
                    null, null, null, null);
            JobPostingEntity saved = service.update(POSTING_ID, cmd, USER_ID);
            assertThat(saved.getTitle()).isEqualTo("新タイトル");
            assertThat(saved.getBaseRewardJpy()).isEqualTo(5000);
        }

        @Test
        @DisplayName("異常系: 応募者1件以上ある求人で報酬変更すると JOB_INVALID_STATE_TRANSITION")
        void 応募あり_報酬変更_拒否() {
            JobPostingEntity posting = postingWithStatus(JobPostingStatus.OPEN);
            given(postingRepository.findById(POSTING_ID)).willReturn(Optional.of(posting));
            given(jobPolicy.canEditPosting(USER_ID, posting)).willReturn(true);
            given(applicationRepository.countByJobPostingId(POSTING_ID)).willReturn(1);

            UpdateJobPostingCommand cmd = new UpdateJobPostingCommand(
                    null, null, null, null, null, null, null, null, 5000,
                    null, null, null, null);

            assertThatThrownBy(() -> service.update(POSTING_ID, cmd, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_INVALID_STATE_TRANSITION));
        }

        @Test
        @DisplayName("正常系: 応募者ありでもタイトルなど軽微属性は変更可能")
        void 応募あり_軽微属性_可() {
            JobPostingEntity posting = postingWithStatus(JobPostingStatus.OPEN);
            given(postingRepository.findById(POSTING_ID)).willReturn(Optional.of(posting));
            given(jobPolicy.canEditPosting(USER_ID, posting)).willReturn(true);
            given(applicationRepository.countByJobPostingId(POSTING_ID)).willReturn(2);
            given(postingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            UpdateJobPostingCommand cmd = new UpdateJobPostingCommand(
                    "新タイトル", "新説明", null, null, null, null, null, null, null,
                    null, null, null, null);
            JobPostingEntity saved = service.update(POSTING_ID, cmd, USER_ID);
            assertThat(saved.getTitle()).isEqualTo("新タイトル");
            assertThat(saved.getDescription()).isEqualTo("新説明");
        }

        @Test
        @DisplayName("異常系: CLOSED の求人は編集不可")
        void 求人CLOSED_編集不可() {
            JobPostingEntity posting = postingWithStatus(JobPostingStatus.CLOSED);
            given(postingRepository.findById(POSTING_ID)).willReturn(Optional.of(posting));
            given(jobPolicy.canEditPosting(USER_ID, posting)).willReturn(true);

            UpdateJobPostingCommand cmd = new UpdateJobPostingCommand(
                    "新タイトル", null, null, null, null, null, null, null, null,
                    null, null, null, null);

            assertThatThrownBy(() -> service.update(POSTING_ID, cmd, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_INVALID_STATE_TRANSITION));
        }
    }

    // ---------------------------------------------------------------------
    // delete
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("delete")
    class DeleteTest {

        @Test
        @DisplayName("正常系: 応募者ゼロ件の場合は論理削除可")
        void 正常_応募なし_論理削除() {
            JobPostingEntity posting = postingWithStatus(JobPostingStatus.DRAFT);
            given(postingRepository.findById(POSTING_ID)).willReturn(Optional.of(posting));
            given(jobPolicy.canEditPosting(USER_ID, posting)).willReturn(true);
            given(applicationRepository.countByJobPostingId(POSTING_ID)).willReturn(0);
            given(postingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.delete(POSTING_ID, USER_ID);

            assertThat(posting.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("異常系: 応募者1件以上ある求人は論理削除不可")
        void 応募あり_削除不可() {
            JobPostingEntity posting = postingWithStatus(JobPostingStatus.OPEN);
            given(postingRepository.findById(POSTING_ID)).willReturn(Optional.of(posting));
            given(jobPolicy.canEditPosting(USER_ID, posting)).willReturn(true);
            given(applicationRepository.countByJobPostingId(POSTING_ID)).willReturn(1);

            assertThatThrownBy(() -> service.delete(POSTING_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(JobmatchingErrorCode.JOB_INVALID_STATE_TRANSITION));
        }
    }

    // ---------------------------------------------------------------------
    // テストデータビルダ
    // ---------------------------------------------------------------------

    private CreateJobPostingCommand defaultCreateCommand(VisibilityScope scope, int reward) {
        LocalDateTime now = LocalDateTime.now();
        return new CreateJobPostingCommand(
                TEAM_ID, "テスト求人", "テスト内容", "アルバイト",
                WorkLocationType.ONSITE, "東京都",
                now.plusDays(1),
                now.plusDays(1).plusHours(3),
                RewardType.LUMP_SUM, reward, 1,
                now.plusHours(12),
                scope, null);
    }

    private JobPostingEntity postingWithStatus(JobPostingStatus status) {
        LocalDateTime now = LocalDateTime.now();
        JobPostingEntity entity = JobPostingEntity.builder()
                .teamId(TEAM_ID)
                .createdByUserId(USER_ID)
                .title("テスト求人")
                .description("テスト")
                .workLocationType(WorkLocationType.ONSITE)
                .workAddress("東京都")
                .workStartAt(now.plusDays(1))
                .workEndAt(now.plusDays(1).plusHours(3))
                .rewardType(RewardType.LUMP_SUM)
                .baseRewardJpy(3000)
                .capacity(1)
                .applicationDeadlineAt(now.plusHours(12))
                .visibilityScope(VisibilityScope.TEAM_MEMBERS)
                .status(status)
                .build();
        try {
            Field field = entity.getClass().getSuperclass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, POSTING_ID);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        return entity;
    }
}
