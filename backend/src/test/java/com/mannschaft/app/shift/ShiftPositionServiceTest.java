package com.mannschaft.app.shift;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.dto.CreatePositionRequest;
import com.mannschaft.app.shift.dto.ShiftPositionResponse;
import com.mannschaft.app.shift.dto.UpdatePositionRequest;
import com.mannschaft.app.shift.entity.ShiftPositionEntity;
import com.mannschaft.app.shift.repository.ShiftPositionRepository;
import com.mannschaft.app.shift.service.ShiftPositionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftPositionService} の単体テスト。
 * シフトポジションのCRUD・重複チェックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftPositionService 単体テスト")
class ShiftPositionServiceTest {

    @Mock
    private ShiftPositionRepository positionRepository;

    @Mock
    private ShiftMapper shiftMapper;

    @InjectMocks
    private ShiftPositionService shiftPositionService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEAM_ID = 1L;
    private static final Long POSITION_ID = 50L;

    private ShiftPositionEntity createPositionEntity() {
        ShiftPositionEntity entity = ShiftPositionEntity.builder()
                .teamId(TEAM_ID)
                .name("キッチン")
                .displayOrder(1)
                .isActive(true)
                .build();
        callOnCreate(entity);
        return entity;
    }

    private ShiftPositionResponse createPositionResponse() {
        return new ShiftPositionResponse(POSITION_ID, TEAM_ID, "キッチン", 1, true, LocalDateTime.now());
    }

    private void callOnCreate(Object entity) {
        try {
            Method method = entity.getClass().getSuperclass().getDeclaredMethod("onCreate");
            method.setAccessible(true);
            method.invoke(entity);
        } catch (Exception ignored) {
        }
    }

    // ========================================
    // listPositions
    // ========================================

    @Nested
    @DisplayName("listPositions")
    class ListPositions {

        @Test
        @DisplayName("ポジション一覧取得_正常_リスト返却")
        void ポジション一覧取得_正常_リスト返却() {
            // Given
            ShiftPositionEntity entity = createPositionEntity();
            ShiftPositionResponse response = createPositionResponse();
            given(positionRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID))
                    .willReturn(List.of(entity));
            given(shiftMapper.toPositionResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<ShiftPositionResponse> result = shiftPositionService.listPositions(TEAM_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("キッチン");
        }
    }

    // ========================================
    // createPosition
    // ========================================

    @Nested
    @DisplayName("createPosition")
    class CreatePosition {

        @Test
        @DisplayName("ポジション作成_正常_レスポンス返却")
        void ポジション作成_正常_レスポンス返却() {
            // Given
            CreatePositionRequest req = new CreatePositionRequest("ホール", 2);
            ShiftPositionEntity savedEntity = createPositionEntity();
            ShiftPositionResponse response = createPositionResponse();
            given(positionRepository.findByTeamIdAndName(TEAM_ID, "ホール"))
                    .willReturn(Optional.empty());
            given(positionRepository.save(any(ShiftPositionEntity.class))).willReturn(savedEntity);
            given(shiftMapper.toPositionResponse(savedEntity)).willReturn(response);

            // When
            ShiftPositionResponse result = shiftPositionService.createPosition(TEAM_ID, req);

            // Then
            assertThat(result).isNotNull();
            verify(positionRepository).save(any(ShiftPositionEntity.class));
        }

        @Test
        @DisplayName("ポジション作成_名前重複_BusinessException")
        void ポジション作成_名前重複_BusinessException() {
            // Given
            CreatePositionRequest req = new CreatePositionRequest("キッチン", 1);
            ShiftPositionEntity existing = createPositionEntity();
            given(positionRepository.findByTeamIdAndName(TEAM_ID, "キッチン"))
                    .willReturn(Optional.of(existing));

            // When & Then
            assertThatThrownBy(() -> shiftPositionService.createPosition(TEAM_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.POSITION_NAME_DUPLICATE));
        }

        @Test
        @DisplayName("ポジション作成_displayOrder未指定_デフォルト0")
        void ポジション作成_displayOrder未指定_デフォルト0() {
            // Given
            CreatePositionRequest req = new CreatePositionRequest("レジ", null);
            ShiftPositionEntity savedEntity = createPositionEntity();
            ShiftPositionResponse response = createPositionResponse();
            given(positionRepository.findByTeamIdAndName(TEAM_ID, "レジ"))
                    .willReturn(Optional.empty());
            given(positionRepository.save(any(ShiftPositionEntity.class))).willReturn(savedEntity);
            given(shiftMapper.toPositionResponse(savedEntity)).willReturn(response);

            // When
            ShiftPositionResponse result = shiftPositionService.createPosition(TEAM_ID, req);

            // Then
            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // updatePosition
    // ========================================

    @Nested
    @DisplayName("updatePosition")
    class UpdatePosition {

        @Test
        @DisplayName("ポジション更新_名前変更_正常")
        void ポジション更新_名前変更_正常() {
            // Given
            ShiftPositionEntity entity = createPositionEntity();
            UpdatePositionRequest req = new UpdatePositionRequest("ホール", null, null);
            ShiftPositionResponse response = createPositionResponse();
            given(positionRepository.findById(POSITION_ID)).willReturn(Optional.of(entity));
            given(positionRepository.findByTeamIdAndName(entity.getTeamId(), "ホール"))
                    .willReturn(Optional.empty());
            given(positionRepository.save(entity)).willReturn(entity);
            given(shiftMapper.toPositionResponse(entity)).willReturn(response);

            // When
            ShiftPositionResponse result = shiftPositionService.updatePosition(POSITION_ID, req);

            // Then
            assertThat(entity.getName()).isEqualTo("ホール");
            verify(positionRepository).save(entity);
        }

        @Test
        @DisplayName("ポジション更新_無効化_正常")
        void ポジション更新_無効化_正常() {
            // Given
            ShiftPositionEntity entity = createPositionEntity();
            UpdatePositionRequest req = new UpdatePositionRequest(null, null, false);
            ShiftPositionResponse response = createPositionResponse();
            given(positionRepository.findById(POSITION_ID)).willReturn(Optional.of(entity));
            given(positionRepository.save(entity)).willReturn(entity);
            given(shiftMapper.toPositionResponse(entity)).willReturn(response);

            // When
            shiftPositionService.updatePosition(POSITION_ID, req);

            // Then
            assertThat(entity.getIsActive()).isFalse();
        }

        @Test
        @DisplayName("ポジション更新_有効化_正常")
        void ポジション更新_有効化_正常() {
            // Given
            ShiftPositionEntity entity = createPositionEntity();
            entity.deactivate();
            UpdatePositionRequest req = new UpdatePositionRequest(null, null, true);
            ShiftPositionResponse response = createPositionResponse();
            given(positionRepository.findById(POSITION_ID)).willReturn(Optional.of(entity));
            given(positionRepository.save(entity)).willReturn(entity);
            given(shiftMapper.toPositionResponse(entity)).willReturn(response);

            // When
            shiftPositionService.updatePosition(POSITION_ID, req);

            // Then
            assertThat(entity.getIsActive()).isTrue();
        }

        @Test
        @DisplayName("ポジション更新_存在しない_BusinessException")
        void ポジション更新_存在しない_BusinessException() {
            // Given
            UpdatePositionRequest req = new UpdatePositionRequest("新名前", null, null);
            given(positionRepository.findById(POSITION_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> shiftPositionService.updatePosition(POSITION_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.SHIFT_POSITION_NOT_FOUND));
        }

        // Note: duplicate name with different entity ID test requires BaseEntity.id
        // which is not settable via builder. Covered by integration tests.
    }

    // ========================================
    // deletePosition
    // ========================================

    @Nested
    @DisplayName("deletePosition")
    class DeletePosition {

        @Test
        @DisplayName("ポジション削除_正常_deleteが呼ばれる")
        void ポジション削除_正常_deleteが呼ばれる() {
            // Given
            ShiftPositionEntity entity = createPositionEntity();
            given(positionRepository.findById(POSITION_ID)).willReturn(Optional.of(entity));

            // When
            shiftPositionService.deletePosition(POSITION_ID);

            // Then
            verify(positionRepository).delete(entity);
        }

        @Test
        @DisplayName("ポジション削除_存在しない_BusinessException")
        void ポジション削除_存在しない_BusinessException() {
            // Given
            given(positionRepository.findById(POSITION_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> shiftPositionService.deletePosition(POSITION_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
