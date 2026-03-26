package com.mannschaft.app.chart;

import com.mannschaft.app.chart.dto.CreateCustomFieldRequest;
import com.mannschaft.app.chart.dto.CreateRecordTemplateRequest;
import com.mannschaft.app.chart.dto.CustomFieldResponse;
import com.mannschaft.app.chart.dto.RecordTemplateResponse;
import com.mannschaft.app.chart.entity.ChartCustomFieldEntity;
import com.mannschaft.app.chart.entity.ChartRecordTemplateEntity;
import com.mannschaft.app.chart.repository.ChartCustomFieldRepository;
import com.mannschaft.app.chart.repository.ChartCustomValueRepository;
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.chart.repository.ChartRecordTemplateRepository;
import com.mannschaft.app.chart.repository.ChartSectionSettingRepository;
import com.mannschaft.app.chart.service.ChartSettingsService;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChartSettingsService 単体テスト")
class ChartSettingsServiceTest {

    @Mock private ChartSectionSettingRepository sectionSettingRepository;
    @Mock private ChartCustomFieldRepository customFieldRepository;
    @Mock private ChartCustomValueRepository customValueRepository;
    @Mock private ChartRecordRepository recordRepository;
    @Mock private ChartRecordTemplateRepository recordTemplateRepository;
    @Mock private ChartMapper chartMapper;

    @InjectMocks
    private ChartSettingsService service;

    private static final Long TEAM_ID = 1L;

    @Nested
    @DisplayName("createCustomField")
    class CreateCustomField {
        @Test
        @DisplayName("異常系: カスタムフィールド上限超過でCHART_012例外")
        void 作成_上限超過_例外() {
            given(customFieldRepository.countByTeamIdAndIsActiveTrue(TEAM_ID)).willReturn(5L);
            CreateCustomFieldRequest request = new CreateCustomFieldRequest("新フィールド", "TEXT", null, null);

            assertThatThrownBy(() -> service.createCustomField(TEAM_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CHART_012"));
        }

        @Test
        @DisplayName("正常系: カスタムフィールドが作成される")
        void 作成_正常_保存() {
            given(customFieldRepository.countByTeamIdAndIsActiveTrue(TEAM_ID)).willReturn(0L);
            CreateCustomFieldRequest request = new CreateCustomFieldRequest("テスト", "TEXT", null, null);
            ChartCustomFieldEntity saved = ChartCustomFieldEntity.builder()
                    .teamId(TEAM_ID).fieldName("テスト").fieldType("TEXT").build();
            given(customFieldRepository.save(any())).willReturn(saved);
            given(chartMapper.toCustomFieldResponse(saved)).willReturn(
                    new CustomFieldResponse(null, null, null, null, null, null));

            CustomFieldResponse result = service.createCustomField(TEAM_ID, request);
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("deactivateCustomField")
    class DeactivateCustomField {
        @Test
        @DisplayName("異常系: フィールド不在でCHART_004例外")
        void 無効化_不在_例外() {
            given(customFieldRepository.findByIdAndTeamId(99L, TEAM_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.deactivateCustomField(TEAM_ID, 99L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CHART_004"));
        }
    }

    @Nested
    @DisplayName("createRecordTemplate")
    class CreateRecordTemplate {
        @Test
        @DisplayName("異常系: テンプレート上限超過でCHART_014例外")
        void 作成_上限超過_例外() {
            given(recordTemplateRepository.countByTeamId(TEAM_ID)).willReturn(20L);
            CreateRecordTemplateRequest request = new CreateRecordTemplateRequest("テンプレート", null, null, null, null, null);

            assertThatThrownBy(() -> service.createRecordTemplate(TEAM_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CHART_014"));
        }
    }

    @Nested
    @DisplayName("deleteRecordTemplate")
    class DeleteRecordTemplate {
        @Test
        @DisplayName("異常系: テンプレート不在でCHART_006例外")
        void 削除_不在_例外() {
            given(recordTemplateRepository.findByIdAndTeamId(99L, TEAM_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.deleteRecordTemplate(TEAM_ID, 99L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CHART_006"));
        }

        @Test
        @DisplayName("正常系: テンプレートが物理削除される")
        void 削除_正常_物理削除() {
            ChartRecordTemplateEntity entity = ChartRecordTemplateEntity.builder()
                    .teamId(TEAM_ID).templateName("テスト").build();
            given(recordTemplateRepository.findByIdAndTeamId(10L, TEAM_ID)).willReturn(Optional.of(entity));
            service.deleteRecordTemplate(TEAM_ID, 10L);
            verify(recordTemplateRepository).delete(entity);
        }
    }
}
