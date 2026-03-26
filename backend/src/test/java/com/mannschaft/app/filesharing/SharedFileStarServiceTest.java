package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.dto.StarResponse;
import com.mannschaft.app.filesharing.entity.SharedFileStarEntity;
import com.mannschaft.app.filesharing.repository.SharedFileStarRepository;
import com.mannschaft.app.filesharing.service.SharedFileStarService;
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

/**
 * {@link SharedFileStarService} の単体テスト。
 * スターの追加・削除を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SharedFileStarService 単体テスト")
class SharedFileStarServiceTest {

    @Mock
    private SharedFileStarRepository starRepository;

    @Mock
    private FileSharingMapper fileSharingMapper;

    @InjectMocks
    private SharedFileStarService sharedFileStarService;

    private static final Long FILE_ID = 100L;
    private static final Long USER_ID = 10L;

    @Nested
    @DisplayName("addStar")
    class AddStar {

        @Test
        @DisplayName("スター追加_正常_レスポンス返却")
        void スター追加_正常_レスポンス返却() {
            // Given
            SharedFileStarEntity savedEntity = SharedFileStarEntity.builder()
                    .fileId(FILE_ID).userId(USER_ID).build();
            StarResponse response = new StarResponse(1L, FILE_ID, USER_ID, null);

            given(starRepository.existsByFileIdAndUserId(FILE_ID, USER_ID)).willReturn(false);
            given(starRepository.save(any(SharedFileStarEntity.class))).willReturn(savedEntity);
            given(fileSharingMapper.toStarResponse(savedEntity)).willReturn(response);

            // When
            StarResponse result = sharedFileStarService.addStar(FILE_ID, USER_ID);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("スター追加_重複_BusinessException")
        void スター追加_重複_BusinessException() {
            // Given
            given(starRepository.existsByFileIdAndUserId(FILE_ID, USER_ID)).willReturn(true);

            // When & Then
            assertThatThrownBy(() -> sharedFileStarService.addStar(FILE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.STAR_ALREADY_EXISTS));
        }
    }

    @Nested
    @DisplayName("removeStar")
    class RemoveStar {

        @Test
        @DisplayName("スター削除_正常_削除実行")
        void スター削除_正常_削除実行() {
            // Given
            SharedFileStarEntity entity = SharedFileStarEntity.builder()
                    .fileId(FILE_ID).userId(USER_ID).build();
            given(starRepository.findByFileIdAndUserId(FILE_ID, USER_ID)).willReturn(Optional.of(entity));

            // When
            sharedFileStarService.removeStar(FILE_ID, USER_ID);

            // Then
            verify(starRepository).delete(entity);
        }

        @Test
        @DisplayName("スター削除_存在しない_BusinessException")
        void スター削除_存在しない_BusinessException() {
            // Given
            given(starRepository.findByFileIdAndUserId(FILE_ID, USER_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> sharedFileStarService.removeStar(FILE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.STAR_NOT_FOUND));
        }
    }
}
