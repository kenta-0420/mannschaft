package com.mannschaft.app.moderation;

import com.mannschaft.app.moderation.dto.ReportResponse;
import com.mannschaft.app.moderation.entity.ContentReportEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ModerationMapper} の単体テスト。
 * MapStruct生成実装によるEntity→DTO変換を検証する。
 */
@DisplayName("ModerationMapper 単体テスト")
class ModerationMapperTest {

    private final ModerationMapper mapper = Mappers.getMapper(ModerationMapper.class);

    // ========================================
    // toReportResponse
    // ========================================

    @Nested
    @DisplayName("toReportResponse")
    class ToReportResponse {

        @Test
        @DisplayName("正常系: ContentReportEntity → ReportResponse 変換")
        void contentReportEntityToReportResponse() {
            // given
            ContentReportEntity entity = ContentReportEntity.builder()
                    .targetType(ReportTargetType.TIMELINE_POST)
                    .targetId(50L)
                    .reportedBy(100L)
                    .scopeType("TEAM")
                    .scopeId(10L)
                    .reason(ReportReason.SPAM)
                    .status(ReportStatus.PENDING)
                    .contentHidden(false)
                    .build();

            // when
            ReportResponse result = mapper.toReportResponse(entity);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getTargetType()).isEqualTo("TIMELINE_POST");
            assertThat(result.getReason()).isEqualTo("SPAM");
            assertThat(result.getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("正常系: HARASSMENT理由・REVIEWING状態で変換")
        void contentReportEntityHarassmentToReportResponse() {
            // given
            ContentReportEntity entity = ContentReportEntity.builder()
                    .targetType(ReportTargetType.TIMELINE_COMMENT)
                    .targetId(99L)
                    .reportedBy(200L)
                    .scopeType("ORGANIZATION")
                    .scopeId(5L)
                    .reason(ReportReason.HARASSMENT)
                    .status(ReportStatus.REVIEWING)
                    .contentHidden(true)
                    .build();

            // when
            ReportResponse result = mapper.toReportResponse(entity);

            // then
            assertThat(result.getTargetType()).isEqualTo("TIMELINE_COMMENT");
            assertThat(result.getReason()).isEqualTo("HARASSMENT");
            assertThat(result.getStatus()).isEqualTo("REVIEWING");
        }

        @Test
        @DisplayName("正常系: リスト変換")
        void contentReportEntityListToReportResponseList() {
            // given
            ContentReportEntity e1 = ContentReportEntity.builder()
                    .targetType(ReportTargetType.TIMELINE_POST).targetId(1L).reportedBy(10L)
                    .scopeType("TEAM").scopeId(1L).reason(ReportReason.SPAM)
                    .status(ReportStatus.PENDING).contentHidden(false).build();
            ContentReportEntity e2 = ContentReportEntity.builder()
                    .targetType(ReportTargetType.USER).targetId(2L).reportedBy(20L)
                    .scopeType("TEAM").scopeId(1L).reason(ReportReason.INAPPROPRIATE)
                    .status(ReportStatus.RESOLVED).contentHidden(false).build();

            // when
            List<ReportResponse> result = mapper.toReportResponseList(List.of(e1, e2));

            // then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getTargetType()).isEqualTo("TIMELINE_POST");
            assertThat(result.get(1).getTargetType()).isEqualTo("USER");
            assertThat(result.get(1).getStatus()).isEqualTo("RESOLVED");
        }
    }
}
