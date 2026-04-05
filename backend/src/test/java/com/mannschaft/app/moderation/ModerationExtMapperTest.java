package com.mannschaft.app.moderation;

import com.mannschaft.app.moderation.dto.AppealResponse;
import com.mannschaft.app.moderation.dto.InternalNoteResponse;
import com.mannschaft.app.moderation.dto.ModerationSettingsResponse;
import com.mannschaft.app.moderation.dto.ModerationTemplateResponse;
import com.mannschaft.app.moderation.dto.SettingsHistoryResponse;
import com.mannschaft.app.moderation.dto.ViolationResponse;
import com.mannschaft.app.moderation.dto.WarningReReviewResponse;
import com.mannschaft.app.moderation.dto.YabaiUnflagResponse;
import com.mannschaft.app.moderation.entity.ModerationActionTemplateEntity;
import com.mannschaft.app.moderation.entity.ModerationAppealEntity;
import com.mannschaft.app.moderation.entity.ModerationSettingsEntity;
import com.mannschaft.app.moderation.entity.ModerationSettingsHistoryEntity;
import com.mannschaft.app.moderation.entity.ReportInternalNoteEntity;
import com.mannschaft.app.moderation.entity.UserViolationEntity;
import com.mannschaft.app.moderation.entity.WarningReReviewEntity;
import com.mannschaft.app.moderation.entity.YabaiUnflagRequestEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ModerationExtMapper} の単体テスト。
 * MapStruct生成実装によるEntity→DTO変換を検証する。
 */
@DisplayName("ModerationExtMapper 単体テスト")
class ModerationExtMapperTest {

    private final ModerationExtMapper mapper = Mappers.getMapper(ModerationExtMapper.class);

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 27, 10, 0);

    // ========================================
    // toViolationResponse
    // ========================================

    @Nested
    @DisplayName("toViolationResponse")
    class ToViolationResponse {

        @Test
        @DisplayName("正常系: UserViolationEntity → ViolationResponse 変換")
        void userViolationEntityToViolationResponse() {
            // given
            UserViolationEntity entity = UserViolationEntity.builder()
                    .userId(100L)
                    .reportId(10L)
                    .actionId(20L)
                    .violationType(ViolationType.WARNING)
                    .reason("スパム投稿")
                    .isActive(true)
                    .build();

            // when
            ViolationResponse result = mapper.toViolationResponse(entity);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getViolationType()).isEqualTo("WARNING");
            assertThat(result.getUserId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("正常系: TEMPORARY_FREEZE タイプの変換")
        void temporaryFreezeViolationEntityToViolationResponse() {
            // given
            UserViolationEntity entity = UserViolationEntity.builder()
                    .userId(200L).reportId(30L).actionId(40L)
                    .violationType(ViolationType.TEMPORARY_FREEZE).reason("深刻な違反")
                    .expiresAt(NOW.plusDays(30)).isActive(true).build();

            // when
            ViolationResponse result = mapper.toViolationResponse(entity);

            // then
            assertThat(result.getViolationType()).isEqualTo("TEMPORARY_FREEZE");
        }

        @Test
        @DisplayName("正常系: リスト変換")
        void userViolationEntityListToViolationResponseList() {
            // given
            UserViolationEntity e1 = UserViolationEntity.builder()
                    .userId(10L).reportId(1L).actionId(2L)
                    .violationType(ViolationType.USER_FREEZE).reason("凍結").isActive(false).build();
            UserViolationEntity e2 = UserViolationEntity.builder()
                    .userId(20L).reportId(3L).actionId(4L)
                    .violationType(ViolationType.WARNING).reason("警告").isActive(true).build();

            // when
            List<ViolationResponse> result = mapper.toViolationResponseList(List.of(e1, e2));

            // then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getViolationType()).isEqualTo("USER_FREEZE");
            assertThat(result.get(1).getViolationType()).isEqualTo("WARNING");
        }
    }

    // ========================================
    // toAppealResponse
    // ========================================

    @Nested
    @DisplayName("toAppealResponse")
    class ToAppealResponse {

        @Test
        @DisplayName("正常系: ModerationAppealEntity → AppealResponse 変換")
        void moderationAppealEntityToAppealResponse() {
            // given
            ModerationAppealEntity entity = ModerationAppealEntity.builder()
                    .userId(100L)
                    .reportId(10L)
                    .actionId(20L)
                    .appealToken("token-abc")
                    .appealTokenExpiresAt(NOW.plusDays(7))
                    .status(AppealStatus.PENDING)
                    .build();

            // when
            AppealResponse result = mapper.toAppealResponse(entity);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo("PENDING");
            assertThat(result.getUserId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("正常系: ACCEPTED ステータスの変換")
        void acceptedAppealEntityToAppealResponse() {
            // given
            ModerationAppealEntity entity = ModerationAppealEntity.builder()
                    .userId(200L).reportId(5L).actionId(6L)
                    .appealToken("accepted-token").appealTokenExpiresAt(NOW)
                    .status(AppealStatus.ACCEPTED)
                    .reviewedBy(999L).reviewNote("問題なし").reviewedAt(NOW).build();

            // when
            AppealResponse result = mapper.toAppealResponse(entity);

            // then
            assertThat(result.getStatus()).isEqualTo("ACCEPTED");
        }

        @Test
        @DisplayName("正常系: リスト変換")
        void moderationAppealEntityListToAppealResponseList() {
            // given
            ModerationAppealEntity entity = ModerationAppealEntity.builder()
                    .userId(100L).reportId(1L).actionId(2L)
                    .appealToken("t1").appealTokenExpiresAt(NOW.plusDays(1))
                    .status(AppealStatus.REJECTED).build();

            // when
            List<AppealResponse> result = mapper.toAppealResponseList(List.of(entity));

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo("REJECTED");
        }
    }

    // ========================================
    // toWarningReReviewResponse
    // ========================================

    @Nested
    @DisplayName("toWarningReReviewResponse")
    class ToWarningReReviewResponse {

        @Test
        @DisplayName("正常系: WarningReReviewEntity → WarningReReviewResponse 変換")
        void warningReReviewEntityToWarningReReviewResponse() {
            // given
            WarningReReviewEntity entity = WarningReReviewEntity.builder()
                    .userId(100L)
                    .reportId(20L)
                    .actionId(30L)
                    .reason("不当な警告")
                    .status(ReReviewStatus.PENDING)
                    .build();

            // when
            WarningReReviewResponse result = mapper.toWarningReReviewResponse(entity);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo("PENDING");
            assertThat(result.getUserId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("正常系: OVERTURNED ステータスの変換")
        void overturnedReReviewEntityToWarningReReviewResponse() {
            // given
            WarningReReviewEntity entity = WarningReReviewEntity.builder()
                    .userId(200L).reportId(1L).actionId(2L).reason("理由")
                    .status(ReReviewStatus.OVERTURNED)
                    .adminReviewedBy(999L).adminReviewNote("覆す").build();

            // when
            WarningReReviewResponse result = mapper.toWarningReReviewResponse(entity);

            // then
            assertThat(result.getStatus()).isEqualTo("OVERTURNED");
        }

        @Test
        @DisplayName("正常系: リスト変換")
        void warningReReviewEntityListToWarningReReviewResponseList() {
            // given
            WarningReReviewEntity entity = WarningReReviewEntity.builder()
                    .userId(10L).reportId(1L).actionId(1L).reason("再審査")
                    .status(ReReviewStatus.ESCALATED).build();

            // when
            List<WarningReReviewResponse> result = mapper.toWarningReReviewResponseList(List.of(entity));

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo("ESCALATED");
        }
    }

    // ========================================
    // toYabaiUnflagResponse
    // ========================================

    @Nested
    @DisplayName("toYabaiUnflagResponse")
    class ToYabaiUnflagResponse {

        @Test
        @DisplayName("正常系: YabaiUnflagRequestEntity → YabaiUnflagResponse 変換")
        void yabaiUnflagRequestEntityToYabaiUnflagResponse() {
            // given
            YabaiUnflagRequestEntity entity = YabaiUnflagRequestEntity.builder()
                    .userId(100L)
                    .reason("フラグ解除申請")
                    .status(UnflagRequestStatus.PENDING)
                    .build();

            // when
            YabaiUnflagResponse result = mapper.toYabaiUnflagResponse(entity);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo("PENDING");
            assertThat(result.getUserId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("正常系: ACCEPTED ステータスの変換")
        void acceptedYabaiUnflagRequestEntityToYabaiUnflagResponse() {
            // given
            YabaiUnflagRequestEntity entity = YabaiUnflagRequestEntity.builder()
                    .userId(200L).reason("更生しました").status(UnflagRequestStatus.ACCEPTED)
                    .reviewedBy(999L).reviewNote("承認").build();

            // when
            List<YabaiUnflagResponse> result = mapper.toYabaiUnflagResponseList(List.of(entity));

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo("ACCEPTED");
        }
    }

    // ========================================
    // toSettingsResponse
    // ========================================

    @Nested
    @DisplayName("toSettingsResponse")
    class ToSettingsResponse {

        @Test
        @DisplayName("正常系: ModerationSettingsEntity → ModerationSettingsResponse 変換")
        void moderationSettingsEntityToModerationSettingsResponse() {
            // given
            ModerationSettingsEntity entity = ModerationSettingsEntity.builder()
                    .settingKey("yabai_threshold")
                    .settingValue("3")
                    .description("やばい閾値")
                    .updatedBy(100L)
                    .build();

            // when
            ModerationSettingsResponse result = mapper.toSettingsResponse(entity);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getSettingKey()).isEqualTo("yabai_threshold");
            assertThat(result.getSettingValue()).isEqualTo("3");
        }

        @Test
        @DisplayName("正常系: リスト変換")
        void moderationSettingsEntityListToModerationSettingsResponseList() {
            // given
            ModerationSettingsEntity e1 = ModerationSettingsEntity.builder()
                    .settingKey("key1").settingValue("v1").build();
            ModerationSettingsEntity e2 = ModerationSettingsEntity.builder()
                    .settingKey("key2").settingValue("v2").build();

            // when
            List<ModerationSettingsResponse> result = mapper.toSettingsResponseList(List.of(e1, e2));

            // then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getSettingKey()).isEqualTo("key1");
            assertThat(result.get(1).getSettingKey()).isEqualTo("key2");
        }
    }

    // ========================================
    // toTemplateResponse
    // ========================================

    @Nested
    @DisplayName("toTemplateResponse")
    class ToTemplateResponse {

        @Test
        @DisplayName("正常系: ModerationActionTemplateEntity → ModerationTemplateResponse 変換")
        void moderationActionTemplateEntityToModerationTemplateResponse() {
            // given
            ModerationActionTemplateEntity entity = ModerationActionTemplateEntity.builder()
                    .name("警告テンプレート")
                    .actionType("WARNING")
                    .reason("スパム")
                    .templateText("このアカウントに警告を発しています")
                    .language("ja")
                    .isDefault(true)
                    .createdBy(1L)
                    .build();

            // when
            ModerationTemplateResponse result = mapper.toTemplateResponse(entity);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("警告テンプレート");
            assertThat(result.getActionType()).isEqualTo("WARNING");
        }

        @Test
        @DisplayName("正常系: リスト変換")
        void moderationActionTemplateEntityListToModerationTemplateResponseList() {
            // given
            ModerationActionTemplateEntity entity = ModerationActionTemplateEntity.builder()
                    .name("BANテンプレート").actionType("BAN").templateText("BANします")
                    .language("ja").isDefault(false).createdBy(1L).build();

            // when
            List<ModerationTemplateResponse> result = mapper.toTemplateResponseList(List.of(entity));

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("BANテンプレート");
        }
    }

    // ========================================
    // toInternalNoteResponse
    // ========================================

    @Nested
    @DisplayName("toInternalNoteResponse")
    class ToInternalNoteResponse {

        @Test
        @DisplayName("正常系: ReportInternalNoteEntity → InternalNoteResponse 変換")
        void reportInternalNoteEntityToInternalNoteResponse() {
            // given
            ReportInternalNoteEntity entity = ReportInternalNoteEntity.builder()
                    .reportId(50L)
                    .authorId(100L)
                    .note("内部メモ: 確認中")
                    .build();

            // when
            InternalNoteResponse result = mapper.toInternalNoteResponse(entity);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getReportId()).isEqualTo(50L);
            assertThat(result.getNote()).isEqualTo("内部メモ: 確認中");
        }

        @Test
        @DisplayName("正常系: リスト変換")
        void reportInternalNoteEntityListToInternalNoteResponseList() {
            // given
            ReportInternalNoteEntity entity = ReportInternalNoteEntity.builder()
                    .reportId(1L).authorId(10L).note("メモ").build();

            // when
            List<InternalNoteResponse> result = mapper.toInternalNoteResponseList(List.of(entity));

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getNote()).isEqualTo("メモ");
        }
    }

    // ========================================
    // toSettingsHistoryResponse
    // ========================================

    @Nested
    @DisplayName("toSettingsHistoryResponse")
    class ToSettingsHistoryResponse {

        @Test
        @DisplayName("正常系: ModerationSettingsHistoryEntity → SettingsHistoryResponse 変換")
        void moderationSettingsHistoryEntityToSettingsHistoryResponse() {
            // given
            ModerationSettingsHistoryEntity entity = ModerationSettingsHistoryEntity.builder()
                    .settingKey("yabai_threshold")
                    .oldValue("3")
                    .newValue("5")
                    .changedBy(100L)
                    .build();

            // when
            SettingsHistoryResponse result = mapper.toSettingsHistoryResponse(entity);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getSettingKey()).isEqualTo("yabai_threshold");
            assertThat(result.getOldValue()).isEqualTo("3");
            assertThat(result.getNewValue()).isEqualTo("5");
            assertThat(result.getChangedBy()).isEqualTo(100L);
        }
    }
}
