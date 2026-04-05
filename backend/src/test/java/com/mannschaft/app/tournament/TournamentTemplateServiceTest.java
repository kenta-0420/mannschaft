package com.mannschaft.app.tournament;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.tournament.entity.SystemTournamentPresetEntity;
import com.mannschaft.app.tournament.entity.TournamentTemplateEntity;
import com.mannschaft.app.tournament.repository.*;
import com.mannschaft.app.tournament.service.TournamentTemplateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link TournamentTemplateService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentTemplateService 単体テスト")
class TournamentTemplateServiceTest {

    @Mock private TournamentTemplateRepository templateRepository;
    @Mock private TournamentTemplateTiebreakerRepository tiebreakerRepository;
    @Mock private TournamentTemplateStatDefRepository statDefRepository;
    @Mock private SystemTournamentPresetRepository presetRepository;
    @Mock private SystemTournamentPresetTiebreakerRepository presetTiebreakerRepository;
    @Mock private SystemTournamentPresetStatDefRepository presetStatDefRepository;
    @Mock private TournamentMapper mapper;

    @InjectMocks
    private TournamentTemplateService service;

    private static final Long ORG_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final Long TEMPLATE_ID = 100L;

    @Nested
    @DisplayName("deleteTemplate")
    class DeleteTemplate {

        @Test
        @DisplayName("正常系: テンプレート論理削除成功")
        void 論理削除成功() {
            TournamentTemplateEntity entity = TournamentTemplateEntity.builder()
                    .organizationId(ORG_ID).name("テスト").build();
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(entity));
            given(templateRepository.save(any())).willReturn(entity);

            service.deleteTemplate(TEMPLATE_ID);

            verify(templateRepository).save(any());
        }

        @Test
        @DisplayName("異常系: テンプレートが見つからない")
        void テンプレート不存在() {
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteTemplate(TEMPLATE_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.TEMPLATE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("cloneFromPreset")
    class CloneFromPreset {

        @Test
        @DisplayName("異常系: プリセットが見つからない")
        void プリセット不存在() {
            given(presetRepository.findById(99L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.cloneFromPreset(ORG_ID, USER_ID, 99L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.PRESET_NOT_FOUND);
        }

        @Test
        @DisplayName("正常系: プリセットからテンプレートが複製される")
        void プリセットから複製() {
            SystemTournamentPresetEntity preset = SystemTournamentPresetEntity.builder()
                    .name("サッカー").winPoints(3).drawPoints(1).lossPoints(0)
                    .hasDraw(true).hasSets(false).hasExtraTime(false).hasPenalties(false)
                    .scoreUnitLabel("点").build();
            given(presetRepository.findById(1L)).willReturn(Optional.of(preset));
            TournamentTemplateEntity saved = TournamentTemplateEntity.builder()
                    .organizationId(ORG_ID).name("サッカー").build();
            given(templateRepository.save(any())).willReturn(saved);
            given(presetTiebreakerRepository.findByPresetIdOrderByPriorityAsc(1L)).willReturn(List.of());
            given(presetStatDefRepository.findByPresetIdOrderBySortOrderAsc(1L)).willReturn(List.of());
            given(templateRepository.findById(any())).willReturn(Optional.of(saved));
            given(tiebreakerRepository.findByTemplateIdOrderByPriorityAsc(any())).willReturn(List.of());
            given(statDefRepository.findByTemplateIdOrderBySortOrderAsc(any())).willReturn(List.of());
            given(mapper.toTemplateResponse(any(), any(), any())).willReturn(null);

            service.cloneFromPreset(ORG_ID, USER_ID, 1L);

            verify(templateRepository).save(any());
        }
    }
}
