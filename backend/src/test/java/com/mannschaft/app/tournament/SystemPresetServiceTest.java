package com.mannschaft.app.tournament;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.tournament.entity.SystemTournamentPresetEntity;
import com.mannschaft.app.tournament.repository.SystemTournamentPresetRepository;
import com.mannschaft.app.tournament.repository.SystemTournamentPresetStatDefRepository;
import com.mannschaft.app.tournament.repository.SystemTournamentPresetTiebreakerRepository;
import com.mannschaft.app.tournament.service.SystemPresetService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link SystemPresetService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SystemPresetService 単体テスト")
class SystemPresetServiceTest {

    @Mock private SystemTournamentPresetRepository presetRepository;
    @Mock private SystemTournamentPresetTiebreakerRepository tiebreakerRepository;
    @Mock private SystemTournamentPresetStatDefRepository statDefRepository;
    @Mock private TournamentMapper mapper;

    @InjectMocks
    private SystemPresetService service;

    private static final Long PRESET_ID = 1L;

    @Nested
    @DisplayName("getPreset")
    class GetPreset {

        @Test
        @DisplayName("異常系: プリセットが見つからない")
        void プリセット不存在() {
            given(presetRepository.findById(PRESET_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPreset(PRESET_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.PRESET_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("deletePreset")
    class DeletePreset {

        @Test
        @DisplayName("正常系: プリセット論理削除成功")
        void 論理削除成功() {
            SystemTournamentPresetEntity entity = SystemTournamentPresetEntity.builder()
                    .name("サッカー").build();
            given(presetRepository.findById(PRESET_ID)).willReturn(Optional.of(entity));
            given(presetRepository.save(any())).willReturn(entity);

            service.deletePreset(PRESET_ID);

            verify(presetRepository).save(any());
        }
    }
}
