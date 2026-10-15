package com.mannschaft.app.matching;

import com.mannschaft.app.matching.dto.NotificationPreferenceResponse;
import com.mannschaft.app.matching.dto.UpdateNotificationPreferenceRequest;
import com.mannschaft.app.matching.entity.MatchNotificationPreferenceEntity;
import com.mannschaft.app.matching.mapper.MatchingMapper;
import com.mannschaft.app.matching.repository.MatchNotificationPreferenceRepository;
import com.mannschaft.app.matching.service.MatchNotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link MatchNotificationService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchNotificationService 単体テスト")
class MatchNotificationServiceTest {

    @Mock
    private MatchNotificationPreferenceRepository preferenceRepository;

    @Mock
    private MatchingMapper matchingMapper;

    @InjectMocks
    private MatchNotificationService service;

    private static final Long TEAM_ID = 1L;

    @Nested
    @DisplayName("getPreference")
    class GetPreference {

        @Test
        @DisplayName("正常系: 設定が存在する場合はマッピング結果を返す")
        void 設定存在時_マッピング結果返却() {
            // Given
            MatchNotificationPreferenceEntity entity = MatchNotificationPreferenceEntity.builder()
                    .teamId(TEAM_ID).build();
            NotificationPreferenceResponse response = new NotificationPreferenceResponse("13", null, null, null, true);
            given(preferenceRepository.findByTeamId(TEAM_ID)).willReturn(Optional.of(entity));
            given(matchingMapper.toNotificationPreferenceResponse(entity)).willReturn(response);

            // When
            NotificationPreferenceResponse result = service.getPreference(TEAM_ID);

            // Then
            assertThat(result).isEqualTo(response);
        }

        @Test
        @DisplayName("正常系: 設定未作成時はデフォルト値を返す")
        void 設定未作成時_デフォルト値返却() {
            // Given
            given(preferenceRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());

            // When
            NotificationPreferenceResponse result = service.getPreference(TEAM_ID);

            // Then
            assertThat(result.getIsEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("updatePreference")
    class UpdatePreference {

        @Test
        @DisplayName("正常系: 既存設定がない場合は新規作成される")
        void 新規作成() {
            // Given
            UpdateNotificationPreferenceRequest request = new UpdateNotificationPreferenceRequest(
                    "13", null, "PRACTICE", null, true);
            given(preferenceRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());
            MatchNotificationPreferenceEntity saved = MatchNotificationPreferenceEntity.builder()
                    .teamId(TEAM_ID).build();
            given(preferenceRepository.save(any())).willReturn(saved);
            NotificationPreferenceResponse response = new NotificationPreferenceResponse("13", null, null, null, true);
            given(matchingMapper.toNotificationPreferenceResponse(saved)).willReturn(response);

            // When
            NotificationPreferenceResponse result = service.updatePreference(TEAM_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(preferenceRepository).save(any());
        }
    }
}
