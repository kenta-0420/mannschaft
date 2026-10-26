package com.mannschaft.app.activity;

import com.mannschaft.app.activity.dto.ActivityStatsResponse;
import com.mannschaft.app.activity.repository.ActivityParticipantRepository;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.activity.repository.ActivityTemplateFieldRepository;
import com.mannschaft.app.activity.repository.ActivityTemplateRepository;
import com.mannschaft.app.activity.service.ActivityStatsService;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityStatsService 単体テスト")
class ActivityStatsServiceTest {

    @Mock private ActivityResultRepository resultRepository;
    @Mock private ActivityTemplateFieldRepository fieldRepository;
    @Mock private ActivityTemplateRepository templateRepository;
    @Mock private ActivityParticipantRepository participantRepository;

    @InjectMocks
    private ActivityStatsService service;

    @Nested
    @DisplayName("getStats")
    class GetStats {
        @Test
        @DisplayName("正常系: 統計データが返却される")
        void 統計_正常_返却() {
            given(resultRepository.countByScopeTypeAndScopeId(ActivityScopeType.TEAM, 1L)).willReturn(5L);
            given(templateRepository.findByScopeTypeAndScopeIdOrderBySortOrderAsc(ActivityScopeType.TEAM, 1L))
                    .willReturn(List.of());
            given(resultRepository.findForExport(any(), any(), any(), any(), any(), any())).willReturn(List.of());

            ActivityStatsResponse result = service.getStats(ActivityScopeType.TEAM, 1L, null, null, null, null);
            assertThat(result.getTotalActivities()).isEqualTo(5L);
        }
    }

    @Nested
    @DisplayName("getFieldStats")
    class GetFieldStats {
        @Test
        @DisplayName("異常系: 不正なfield_keyで例外")
        void フィールド統計_不正キー_例外() {
            assertThatThrownBy(() -> service.getFieldStats(ActivityScopeType.TEAM, 1L, 10L, "drop;table", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("異常系: フィールド不在でACTIVITY_002例外")
        void フィールド統計_フィールド不在_例外() {
            given(fieldRepository.findByTemplateIdAndFieldKey(10L, "valid_key")).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getFieldStats(ActivityScopeType.TEAM, 1L, 10L, "valid_key", null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ACTIVITY_002"));
        }
    }
}
