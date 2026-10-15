package com.mannschaft.app.line;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.line.dto.LinkLineRequest;
import com.mannschaft.app.line.dto.UserLineStatusResponse;
import com.mannschaft.app.line.entity.UserLineConnectionEntity;
import com.mannschaft.app.line.repository.UserLineConnectionRepository;
import com.mannschaft.app.line.service.UserLineConnectionService;
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
 * {@link UserLineConnectionService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserLineConnectionService 単体テスト")
class UserLineConnectionServiceTest {

    @Mock
    private UserLineConnectionRepository userLineConnectionRepository;
    @Mock
    private LineMapper lineMapper;

    @InjectMocks
    private UserLineConnectionService service;

    private static final Long USER_ID = 1L;

    @Nested
    @DisplayName("getStatus")
    class GetStatus {

        @Test
        @DisplayName("正常系: 連携済みの場合ステータスが返却される")
        void 取得_連携済み_ステータス返却() {
            // Given
            UserLineConnectionEntity entity = UserLineConnectionEntity.builder()
                    .userId(USER_ID).lineUserId("line123").build();
            UserLineStatusResponse response = new UserLineStatusResponse(
                    true, "line123", "Name", null, null, null, null);
            given(userLineConnectionRepository.findByUserId(USER_ID)).willReturn(Optional.of(entity));
            given(lineMapper.toUserLineStatusResponse(entity)).willReturn(response);

            // When
            UserLineStatusResponse result = service.getStatus(USER_ID);

            // Then
            assertThat(result.getIsLinked()).isTrue();
        }

        @Test
        @DisplayName("正常系: 未連携の場合falseが返却される")
        void 取得_未連携_false返却() {
            // Given
            given(userLineConnectionRepository.findByUserId(USER_ID)).willReturn(Optional.empty());

            // When
            UserLineStatusResponse result = service.getStatus(USER_ID);

            // Then
            assertThat(result.getIsLinked()).isFalse();
        }
    }

    @Nested
    @DisplayName("link")
    class Link {

        @Test
        @DisplayName("正常系: LINEアカウントがリンクされる")
        void リンク_正常_保存() {
            // Given
            LinkLineRequest req = new LinkLineRequest("line123", "Name", null, null);
            given(userLineConnectionRepository.existsByUserId(USER_ID)).willReturn(false);
            given(userLineConnectionRepository.existsByLineUserId("line123")).willReturn(false);
            given(userLineConnectionRepository.save(any(UserLineConnectionEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(lineMapper.toUserLineStatusResponse(any(UserLineConnectionEntity.class)))
                    .willReturn(new UserLineStatusResponse(true, "line123", "Name", null, null, null, null));

            // When
            UserLineStatusResponse result = service.link(USER_ID, req);

            // Then
            assertThat(result.getIsLinked()).isTrue();
            verify(userLineConnectionRepository).save(any(UserLineConnectionEntity.class));
        }

        @Test
        @DisplayName("異常系: 既にLINE連携済みの場合LINE_006例外")
        void リンク_重複_例外() {
            // Given
            LinkLineRequest req = new LinkLineRequest("line123", "Name", null, null);
            given(userLineConnectionRepository.existsByUserId(USER_ID)).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> service.link(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("LINE_006"));
        }
    }

    @Nested
    @DisplayName("unlink")
    class Unlink {

        @Test
        @DisplayName("正常系: LINE連携が解除される")
        void 解除_正常_削除() {
            // Given
            given(userLineConnectionRepository.existsByUserId(USER_ID)).willReturn(true);

            // When
            service.unlink(USER_ID);

            // Then
            verify(userLineConnectionRepository).deleteByUserId(USER_ID);
        }

        @Test
        @DisplayName("異常系: 連携不在でLINE_005例外")
        void 解除_不在_例外() {
            // Given
            given(userLineConnectionRepository.existsByUserId(USER_ID)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> service.unlink(USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("LINE_005"));
        }
    }
}
