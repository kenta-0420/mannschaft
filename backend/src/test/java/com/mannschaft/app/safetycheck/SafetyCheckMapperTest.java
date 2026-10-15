package com.mannschaft.app.safetycheck;

import com.mannschaft.app.safetycheck.dto.SafetyCheckResponse;
import com.mannschaft.app.safetycheck.dto.SafetyFollowupResponse;
import com.mannschaft.app.safetycheck.dto.SafetyPresetResponse;
import com.mannschaft.app.safetycheck.dto.SafetyResponseResponse;
import com.mannschaft.app.safetycheck.dto.SafetyTemplateResponse;
import com.mannschaft.app.safetycheck.entity.SafetyCheckEntity;
import com.mannschaft.app.safetycheck.entity.SafetyCheckMessagePresetEntity;
import com.mannschaft.app.safetycheck.entity.SafetyCheckTemplateEntity;
import com.mannschaft.app.safetycheck.entity.SafetyResponseEntity;
import com.mannschaft.app.safetycheck.entity.SafetyResponseFollowupEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SafetyCheckMapper} の単体テスト。
 * MapStruct生成実装によるEntity→DTO変換を検証する。
 */
@DisplayName("SafetyCheckMapper 単体テスト")
class SafetyCheckMapperTest {

    private final SafetyCheckMapper mapper = Mappers.getMapper(SafetyCheckMapper.class);

    // ========================================
    // toSafetyCheckResponse
    // ========================================

    @Nested
    @DisplayName("toSafetyCheckResponse")
    class ToSafetyCheckResponse {

        @Test
        @DisplayName("正常系: SafetyCheckEntity → SafetyCheckResponse 変換")
        void safetyCheckEntityToSafetyCheckResponse() {
            // given
            SafetyCheckEntity entity = SafetyCheckEntity.builder()
                    .scopeType(SafetyCheckScopeType.TEAM)
                    .scopeId(10L)
                    .title("安否確認テスト")
                    .message("状況を教えてください")
                    .status(SafetyCheckStatus.ACTIVE)
                    .isDrill(false)
                    .totalTargetCount(50)
                    .build();

            // when
            SafetyCheckResponse result = mapper.toSafetyCheckResponse(entity);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getScopeType()).isEqualTo("TEAM");
            assertThat(result.getStatus()).isEqualTo("ACTIVE");
            assertThat(result.getTitle()).isEqualTo("安否確認テスト");
        }

        @Test
        @DisplayName("正常系: ORGANIZATION スコープで変換")
        void organizationScopeEntityToSafetyCheckResponse() {
            // given
            SafetyCheckEntity entity = SafetyCheckEntity.builder()
                    .scopeType(SafetyCheckScopeType.ORGANIZATION)
                    .scopeId(5L)
                    .title("組織安否確認")
                    .status(SafetyCheckStatus.CLOSED)
                    .isDrill(true)
                    .totalTargetCount(200)
                    .build();

            // when
            SafetyCheckResponse result = mapper.toSafetyCheckResponse(entity);

            // then
            assertThat(result.getScopeType()).isEqualTo("ORGANIZATION");
            assertThat(result.getStatus()).isEqualTo("CLOSED");
        }

        @Test
        @DisplayName("正常系: リスト変換")
        void safetyCheckEntityListToSafetyCheckResponseList() {
            // given
            SafetyCheckEntity e1 = SafetyCheckEntity.builder()
                    .scopeType(SafetyCheckScopeType.TEAM).scopeId(1L)
                    .title("確認1").status(SafetyCheckStatus.ACTIVE).isDrill(false).totalTargetCount(10).build();
            SafetyCheckEntity e2 = SafetyCheckEntity.builder()
                    .scopeType(SafetyCheckScopeType.ORGANIZATION).scopeId(2L)
                    .title("確認2").status(SafetyCheckStatus.CLOSED).isDrill(true).totalTargetCount(20).build();

            // when
            List<SafetyCheckResponse> result = mapper.toSafetyCheckResponseList(List.of(e1, e2));

            // then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getTitle()).isEqualTo("確認1");
            assertThat(result.get(1).getTitle()).isEqualTo("確認2");
        }
    }

    // ========================================
    // toSafetyResponseResponse
    // ========================================

    @Nested
    @DisplayName("toSafetyResponseResponse")
    class ToSafetyResponseResponse {

        @Test
        @DisplayName("正常系: messageSource あり → 変換")
        void safetyResponseEntityWithMessageSourceToResponse() {
            // given
            SafetyResponseEntity entity = SafetyResponseEntity.builder()
                    .safetyCheckId(100L)
                    .userId(200L)
                    .status(SafetyResponseStatus.SAFE)
                    .message("無事です")
                    .messageSource(MessageSource.PRESET)
                    .gpsShared(false)
                    .build();

            // when
            SafetyResponseResponse result = mapper.toSafetyResponseResponse(entity);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo("SAFE");
            assertThat(result.getMessageSource()).isEqualTo("PRESET");
        }

        @Test
        @DisplayName("正常系: messageSource null → null 返却")
        void safetyResponseEntityWithNullMessageSourceToResponse() {
            // given
            SafetyResponseEntity entity = SafetyResponseEntity.builder()
                    .safetyCheckId(100L)
                    .userId(300L)
                    .status(SafetyResponseStatus.NEED_SUPPORT)
                    .message("支援が必要です")
                    .messageSource(null)
                    .gpsShared(true)
                    .build();

            // when
            SafetyResponseResponse result = mapper.toSafetyResponseResponse(entity);

            // then
            assertThat(result.getStatus()).isEqualTo("NEED_SUPPORT");
            assertThat(result.getMessageSource()).isNull();
        }

        @Test
        @DisplayName("正常系: リスト変換")
        void safetyResponseEntityListToSafetyResponseResponseList() {
            // given
            SafetyResponseEntity entity = SafetyResponseEntity.builder()
                    .safetyCheckId(1L).userId(100L)
                    .status(SafetyResponseStatus.OTHER)
                    .gpsShared(false).build();

            // when
            List<SafetyResponseResponse> result = mapper.toSafetyResponseResponseList(List.of(entity));

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo("OTHER");
        }
    }

    // ========================================
    // toTemplateResponse
    // ========================================

    @Nested
    @DisplayName("toTemplateResponse")
    class ToTemplateResponse {

        @Test
        @DisplayName("正常系: scopeType あり → 変換")
        void safetyCheckTemplateEntityWithScopeTypeToResponse() {
            // given
            SafetyCheckTemplateEntity entity = SafetyCheckTemplateEntity.builder()
                    .scopeType(SafetyCheckScopeType.TEAM)
                    .scopeId(10L)
                    .templateName("チーム用テンプレート")
                    .title("安否確認")
                    .message("状況を教えてください")
                    .isSystemDefault(false)
                    .sortOrder(0)
                    .build();

            // when
            SafetyTemplateResponse result = mapper.toTemplateResponse(entity);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getScopeType()).isEqualTo("TEAM");
            assertThat(result.getTemplateName()).isEqualTo("チーム用テンプレート");
        }

        @Test
        @DisplayName("正常系: scopeType null → null 返却")
        void safetyCheckTemplateEntityWithNullScopeTypeToResponse() {
            // given
            SafetyCheckTemplateEntity entity = SafetyCheckTemplateEntity.builder()
                    .scopeType(null)
                    .templateName("システムデフォルト")
                    .title("安否確認（デフォルト）")
                    .isSystemDefault(true)
                    .sortOrder(0)
                    .build();

            // when
            SafetyTemplateResponse result = mapper.toTemplateResponse(entity);

            // then
            assertThat(result.getScopeType()).isNull();
            assertThat(result.getTemplateName()).isEqualTo("システムデフォルト");
        }

        @Test
        @DisplayName("正常系: リスト変換")
        void safetyCheckTemplateEntityListToSafetyTemplateResponseList() {
            // given
            SafetyCheckTemplateEntity entity = SafetyCheckTemplateEntity.builder()
                    .scopeType(SafetyCheckScopeType.ORGANIZATION)
                    .templateName("組織テンプレート")
                    .title("テスト")
                    .isSystemDefault(false)
                    .sortOrder(1)
                    .build();

            // when
            List<SafetyTemplateResponse> result = mapper.toTemplateResponseList(List.of(entity));

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getScopeType()).isEqualTo("ORGANIZATION");
        }
    }

    // ========================================
    // toPresetResponse
    // ========================================

    @Nested
    @DisplayName("toPresetResponse")
    class ToPresetResponse {

        @Test
        @DisplayName("正常系: SafetyCheckMessagePresetEntity → SafetyPresetResponse 変換")
        void safetyCheckMessagePresetEntityToSafetyPresetResponse() {
            // given
            SafetyCheckMessagePresetEntity entity = SafetyCheckMessagePresetEntity.builder()
                    .body("無事です")
                    .sortOrder(0)
                    .isActive(true)
                    .build();

            // when
            SafetyPresetResponse result = mapper.toPresetResponse(entity);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getBody()).isEqualTo("無事です");
            assertThat(result.getSortOrder()).isEqualTo(0);
        }

        @Test
        @DisplayName("正常系: リスト変換")
        void safetyCheckMessagePresetEntityListToSafetyPresetResponseList() {
            // given
            SafetyCheckMessagePresetEntity e1 = SafetyCheckMessagePresetEntity.builder()
                    .body("安全").sortOrder(0).isActive(true).build();
            SafetyCheckMessagePresetEntity e2 = SafetyCheckMessagePresetEntity.builder()
                    .body("支援要").sortOrder(1).isActive(true).build();

            // when
            List<SafetyPresetResponse> result = mapper.toPresetResponseList(List.of(e1, e2));

            // then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getBody()).isEqualTo("安全");
            assertThat(result.get(1).getBody()).isEqualTo("支援要");
        }
    }

    // ========================================
    // toFollowupResponse
    // ========================================

    @Nested
    @DisplayName("toFollowupResponse")
    class ToFollowupResponse {

        @Test
        @DisplayName("正常系: SafetyResponseFollowupEntity → SafetyFollowupResponse 変換")
        void safetyResponseFollowupEntityToSafetyFollowupResponse() {
            // given
            SafetyResponseFollowupEntity entity = SafetyResponseFollowupEntity.builder()
                    .safetyResponseId(10L)
                    .followupStatus(FollowupStatus.PENDING)
                    .assignedTo(500L)
                    .note("確認中")
                    .build();

            // when
            SafetyFollowupResponse result = mapper.toFollowupResponse(entity);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getFollowupStatus()).isEqualTo("PENDING");
            assertThat(result.getAssignedTo()).isEqualTo(500L);
        }

        @Test
        @DisplayName("正常系: COMPLETED ステータスのフォローアップ変換")
        void completedFollowupEntityToSafetyFollowupResponse() {
            // given
            SafetyResponseFollowupEntity entity = SafetyResponseFollowupEntity.builder()
                    .safetyResponseId(20L)
                    .followupStatus(FollowupStatus.COMPLETED)
                    .build();

            // when
            List<SafetyFollowupResponse> result = mapper.toFollowupResponseList(List.of(entity));

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getFollowupStatus()).isEqualTo("COMPLETED");
        }
    }

    // ========================================
    // SafetyResponseFollowupEntity.update
    // ========================================

    @Nested
    @DisplayName("SafetyResponseFollowupEntity.update")
    class SafetyResponseFollowupEntityUpdate {

        @Test
        @DisplayName("正常系: 全フィールド更新")
        void updateAllFields() {
            // given
            SafetyResponseFollowupEntity entity = SafetyResponseFollowupEntity.builder()
                    .safetyResponseId(10L)
                    .followupStatus(FollowupStatus.PENDING)
                    .build();

            // when
            entity.update(FollowupStatus.COMPLETED, 500L, "対応完了");

            // then
            assertThat(entity.getFollowupStatus()).isEqualTo(FollowupStatus.COMPLETED);
            assertThat(entity.getAssignedTo()).isEqualTo(500L);
            assertThat(entity.getNote()).isEqualTo("対応完了");
        }

        @Test
        @DisplayName("正常系: nullフィールドは更新されない")
        void updateNullFieldsNotChanged() {
            // given
            SafetyResponseFollowupEntity entity = SafetyResponseFollowupEntity.builder()
                    .safetyResponseId(20L)
                    .followupStatus(FollowupStatus.PENDING)
                    .assignedTo(100L)
                    .note("既存メモ")
                    .build();

            // when
            entity.update(null, null, null);

            // then
            assertThat(entity.getFollowupStatus()).isEqualTo(FollowupStatus.PENDING);
            assertThat(entity.getAssignedTo()).isEqualTo(100L);
            assertThat(entity.getNote()).isEqualTo("既存メモ");
        }
    }
}
