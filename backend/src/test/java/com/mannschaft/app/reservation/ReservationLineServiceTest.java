package com.mannschaft.app.reservation;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.dto.CreateReservationLineRequest;
import com.mannschaft.app.reservation.dto.ReservationLineResponse;
import com.mannschaft.app.reservation.dto.UpdateReservationLineRequest;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.service.ReservationLineService;
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
import static org.mockito.Mockito.verify;

/**
 * {@link ReservationLineService} の単体テスト。
 * 予約ラインのCRUD操作を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationLineService 単体テスト")
class ReservationLineServiceTest {

    @Mock
    private ReservationLineRepository lineRepository;

    @Mock
    private ReservationMapper reservationMapper;

    @InjectMocks
    private ReservationLineService service;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEAM_ID = 1L;
    private static final Long LINE_ID = 10L;
    private static final Long STAFF_USER_ID = 50L;

    private ReservationLineEntity createLineEntity() {
        return ReservationLineEntity.builder()
                .teamId(TEAM_ID)
                .name("カウンセリング")
                .description("個別カウンセリング60分")
                .displayOrder(1)
                .defaultStaffUserId(STAFF_USER_ID)
                .build();
    }

    private ReservationLineResponse createLineResponse() {
        return new ReservationLineResponse(
                LINE_ID, TEAM_ID, "カウンセリング", "個別カウンセリング60分",
                1, true, STAFF_USER_ID, null, null);
    }

    // ========================================
    // listLines
    // ========================================

    @Nested
    @DisplayName("listLines")
    class ListLines {

        @Test
        @DisplayName("正常系: チームの予約ライン一覧が返却される")
        void ライン一覧_正常() {
            // Given
            List<ReservationLineEntity> entities = List.of(createLineEntity());
            List<ReservationLineResponse> responses = List.of(createLineResponse());
            given(lineRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID)).willReturn(entities);
            given(reservationMapper.toLineResponseList(entities)).willReturn(responses);

            // When
            List<ReservationLineResponse> result = service.listLines(TEAM_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("カウンセリング");
        }
    }

    // ========================================
    // listActiveLines
    // ========================================

    @Nested
    @DisplayName("listActiveLines")
    class ListActiveLines {

        @Test
        @DisplayName("正常系: 有効なライン一覧が返却される")
        void 有効ライン一覧_正常() {
            // Given
            List<ReservationLineEntity> entities = List.of(createLineEntity());
            List<ReservationLineResponse> responses = List.of(createLineResponse());
            given(lineRepository.findByTeamIdAndIsActiveTrueOrderByDisplayOrderAsc(TEAM_ID)).willReturn(entities);
            given(reservationMapper.toLineResponseList(entities)).willReturn(responses);

            // When
            List<ReservationLineResponse> result = service.listActiveLines(TEAM_ID);

            // Then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // createLine
    // ========================================

    @Nested
    @DisplayName("createLine")
    class CreateLine {

        @Test
        @DisplayName("正常系: 予約ラインが作成される")
        void ライン作成_正常() {
            // Given
            CreateReservationLineRequest request = new CreateReservationLineRequest(
                    "新メニュー", "説明文", 2, STAFF_USER_ID);
            ReservationLineEntity savedEntity = createLineEntity();
            ReservationLineResponse response = createLineResponse();

            given(lineRepository.save(any(ReservationLineEntity.class))).willReturn(savedEntity);
            given(reservationMapper.toLineResponse(savedEntity)).willReturn(response);

            // When
            ReservationLineResponse result = service.createLine(TEAM_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(lineRepository).save(any(ReservationLineEntity.class));
        }

        @Test
        @DisplayName("正常系: displayOrderがnullの場合デフォルト値1が使用される")
        void ライン作成_表示順デフォルト() {
            // Given
            CreateReservationLineRequest request = new CreateReservationLineRequest(
                    "新メニュー", null, null, null);
            ReservationLineEntity savedEntity = createLineEntity();
            ReservationLineResponse response = createLineResponse();

            given(lineRepository.save(any(ReservationLineEntity.class))).willReturn(savedEntity);
            given(reservationMapper.toLineResponse(savedEntity)).willReturn(response);

            // When
            ReservationLineResponse result = service.createLine(TEAM_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(lineRepository).save(any(ReservationLineEntity.class));
        }
    }

    // ========================================
    // updateLine
    // ========================================

    @Nested
    @DisplayName("updateLine")
    class UpdateLine {

        @Test
        @DisplayName("正常系: ライン名が更新される")
        void ライン更新_名前変更() {
            // Given
            UpdateReservationLineRequest request = new UpdateReservationLineRequest(
                    "更新後メニュー", null, null, null, null);
            ReservationLineEntity entity = createLineEntity();
            ReservationLineResponse response = createLineResponse();

            given(lineRepository.findByIdAndTeamId(LINE_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(lineRepository.save(entity)).willReturn(entity);
            given(reservationMapper.toLineResponse(entity)).willReturn(response);

            // When
            ReservationLineResponse result = service.updateLine(TEAM_ID, LINE_ID, request);

            // Then
            assertThat(result).isNotNull();
            assertThat(entity.getName()).isEqualTo("更新後メニュー");
        }

        @Test
        @DisplayName("正常系: 全フィールドを更新する")
        void ライン更新_全フィールド() {
            // Given
            UpdateReservationLineRequest request = new UpdateReservationLineRequest(
                    "全更新メニュー", "新しい説明", 5, false, 99L);
            ReservationLineEntity entity = createLineEntity();
            ReservationLineResponse response = createLineResponse();

            given(lineRepository.findByIdAndTeamId(LINE_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(lineRepository.save(entity)).willReturn(entity);
            given(reservationMapper.toLineResponse(entity)).willReturn(response);

            // When
            ReservationLineResponse result = service.updateLine(TEAM_ID, LINE_ID, request);

            // Then
            assertThat(result).isNotNull();
            assertThat(entity.getName()).isEqualTo("全更新メニュー");
            assertThat(entity.getDescription()).isEqualTo("新しい説明");
            assertThat(entity.getDisplayOrder()).isEqualTo(5);
            assertThat(entity.getIsActive()).isFalse();
            assertThat(entity.getDefaultStaffUserId()).isEqualTo(99L);
        }

        @Test
        @DisplayName("正常系: isActiveをtrueに設定するとactivate()が呼ばれる")
        void ライン更新_有効化() {
            // Given
            UpdateReservationLineRequest request = new UpdateReservationLineRequest(
                    null, null, null, true, null);
            ReservationLineEntity entity = createLineEntity();
            entity.deactivate();
            ReservationLineResponse response = createLineResponse();

            given(lineRepository.findByIdAndTeamId(LINE_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(lineRepository.save(entity)).willReturn(entity);
            given(reservationMapper.toLineResponse(entity)).willReturn(response);

            // When
            service.updateLine(TEAM_ID, LINE_ID, request);

            // Then
            assertThat(entity.getIsActive()).isTrue();
        }

        @Test
        @DisplayName("異常系: ラインが存在しない場合LINE_NOT_FOUNDエラー")
        void ライン更新_存在しない() {
            // Given
            UpdateReservationLineRequest request = new UpdateReservationLineRequest(
                    "更新", null, null, null, null);
            given(lineRepository.findByIdAndTeamId(LINE_ID, TEAM_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.updateLine(TEAM_ID, LINE_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.LINE_NOT_FOUND);
        }
    }

    // ========================================
    // deleteLine
    // ========================================

    @Nested
    @DisplayName("deleteLine")
    class DeleteLine {

        @Test
        @DisplayName("正常系: ラインが論理削除される")
        void ライン削除_正常() {
            // Given
            ReservationLineEntity entity = createLineEntity();
            given(lineRepository.findByIdAndTeamId(LINE_ID, TEAM_ID)).willReturn(Optional.of(entity));

            // When
            service.deleteLine(TEAM_ID, LINE_ID);

            // Then
            assertThat(entity.getDeletedAt()).isNotNull();
            verify(lineRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: ラインが存在しない場合LINE_NOT_FOUNDエラー")
        void ライン削除_存在しない() {
            // Given
            given(lineRepository.findByIdAndTeamId(LINE_ID, TEAM_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.deleteLine(TEAM_ID, LINE_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.LINE_NOT_FOUND);
        }
    }
}
