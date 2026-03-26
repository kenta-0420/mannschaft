package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timeline.TimelineErrorCode;
import com.mannschaft.app.timeline.TimelineMapper;
import com.mannschaft.app.timeline.dto.MuteResponse;
import com.mannschaft.app.timeline.entity.UserMuteEntity;
import com.mannschaft.app.timeline.repository.UserMuteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link TimelineMuteService} の単体テスト。
 * ミュート追加・解除・一覧取得を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimelineMuteService 単体テスト")
class TimelineMuteServiceTest {

    @Mock
    private UserMuteRepository muteRepository;

    @Mock
    private TimelineMapper timelineMapper;

    @InjectMocks
    private TimelineMuteService timelineMuteService;

    private static final Long USER_ID = 100L;
    private static final Long MUTED_ID = 200L;
    private static final String MUTED_TYPE = "USER";

    // ========================================
    // addMute
    // ========================================
    @Nested
    @DisplayName("addMute")
    class AddMute {

        @Test
        @DisplayName("正常系: ミュートを追加できる")
        void ミュートを追加できる() {
            // given
            UserMuteEntity mute = UserMuteEntity.builder()
                    .userId(USER_ID).mutedType(MUTED_TYPE).mutedId(MUTED_ID).build();
            MuteResponse expected = new MuteResponse(1L, USER_ID, MUTED_TYPE, MUTED_ID, LocalDateTime.now());

            given(muteRepository.existsByUserIdAndMutedTypeAndMutedId(USER_ID, MUTED_TYPE, MUTED_ID))
                    .willReturn(false);
            given(muteRepository.save(any(UserMuteEntity.class))).willReturn(mute);
            given(timelineMapper.toMuteResponse(any(UserMuteEntity.class))).willReturn(expected);

            // when
            MuteResponse result = timelineMuteService.addMute(MUTED_TYPE, MUTED_ID, USER_ID);

            // then
            assertThat(result).isEqualTo(expected);
            verify(muteRepository).save(any(UserMuteEntity.class));
        }

        @Test
        @DisplayName("異常系: 既にミュート済みの場合はエラー")
        void 既にミュート済みの場合はエラー() {
            // given
            given(muteRepository.existsByUserIdAndMutedTypeAndMutedId(USER_ID, MUTED_TYPE, MUTED_ID))
                    .willReturn(true);

            // when & then
            assertThatThrownBy(() -> timelineMuteService.addMute(MUTED_TYPE, MUTED_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.MUTE_ALREADY_EXISTS));
        }
    }

    // ========================================
    // removeMute
    // ========================================
    @Nested
    @DisplayName("removeMute")
    class RemoveMute {

        @Test
        @DisplayName("正常系: ミュートを解除できる")
        void ミュートを解除できる() {
            // given
            UserMuteEntity mute = UserMuteEntity.builder()
                    .userId(USER_ID).mutedType(MUTED_TYPE).mutedId(MUTED_ID).build();
            given(muteRepository.findByUserIdAndMutedTypeAndMutedId(USER_ID, MUTED_TYPE, MUTED_ID))
                    .willReturn(Optional.of(mute));

            // when
            timelineMuteService.removeMute(MUTED_TYPE, MUTED_ID, USER_ID);

            // then
            verify(muteRepository).delete(mute);
        }

        @Test
        @DisplayName("異常系: ミュートが見つからない場合はエラー")
        void ミュートが見つからない場合はエラー() {
            // given
            given(muteRepository.findByUserIdAndMutedTypeAndMutedId(USER_ID, MUTED_TYPE, MUTED_ID))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> timelineMuteService.removeMute(MUTED_TYPE, MUTED_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.MUTE_NOT_FOUND));
        }
    }

    // ========================================
    // getMutes
    // ========================================
    @Nested
    @DisplayName("getMutes")
    class GetMutes {

        @Test
        @DisplayName("正常系: ミュート一覧を取得できる")
        void ミュート一覧を取得できる() {
            // given
            List<UserMuteEntity> mutes = List.of(
                    UserMuteEntity.builder().userId(USER_ID).mutedType(MUTED_TYPE).mutedId(MUTED_ID).build());
            List<MuteResponse> expected = List.of(
                    new MuteResponse(1L, USER_ID, MUTED_TYPE, MUTED_ID, LocalDateTime.now()));

            given(muteRepository.findByUserId(USER_ID)).willReturn(mutes);
            given(timelineMapper.toMuteResponseList(mutes)).willReturn(expected);

            // when
            List<MuteResponse> result = timelineMuteService.getMutes(USER_ID);

            // then
            assertThat(result).hasSize(1);
        }
    }
}
