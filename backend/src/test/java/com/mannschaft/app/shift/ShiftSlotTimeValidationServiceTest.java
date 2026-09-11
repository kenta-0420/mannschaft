package com.mannschaft.app.shift;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.dto.CreateShiftSlotRequest;
import com.mannschaft.app.shift.dto.UpdateShiftSlotRequest;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftAssignmentRepository;
import com.mannschaft.app.shift.repository.ShiftPositionRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shift.service.ShiftSlotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftSlotService} の枠時刻バリデーション結線テスト（試練 / red 先行）。
 *
 * <p>設計 F03.5 §11.2.5。バリデータ単体の規則そのものは
 * {@code ShiftSlotTimeValidatorTest} が持ち、本クラスは<b>サービスが実際にそれを通しているか</b>
 * （＝不正時刻で永続化に到達しないか・部分更新でどう合成されるか）を検証する。</p>
 *
 * <p><b>永続化に到達しないことの測り方</b>: 実 DB の行数ではなく
 * {@code slotRepository.save} が呼ばれないことで測る。同ドメインの金型
 * {@code ShiftSlotServiceTest} が mock ベースであり、実 DB 版は Docker 不在時に
 * skip されて赤にならない（＝試練として機能しない）ため。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftSlotService 枠時刻バリデーション結線テスト")
class ShiftSlotTimeValidationServiceTest {

    @Mock
    private ShiftSlotRepository slotRepository;

    @Mock
    private ShiftPositionRepository positionRepository;

    @Mock
    private ShiftScheduleRepository scheduleRepository;

    @Mock
    private ShiftAssignmentRepository assignmentRepository;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private ShiftSlotService shiftSlotService;

    private static final Long SCHEDULE_ID = 100L;
    private static final Long SLOT_ID = 200L;
    /** 操作者。本テストの主眼は時刻検証であり認可ではないため SYSTEM_ADMIN で短絡させる。 */
    private static final Long ACTOR = 999L;

    @BeforeEach
    void setUpAuthz() {
        lenient().when(accessControlService.isSystemAdmin(ACTOR)).thenReturn(true);
    }

    private ShiftSlotEntity slotEntity(LocalTime start, LocalTime end) {
        return ShiftSlotEntity.builder()
                .scheduleId(SCHEDULE_ID)
                .slotDate(LocalDate.of(2026, 3, 2))
                .startTime(start)
                .endTime(end)
                .requiredCount(1)
                .build();
    }

    @Nested
    @DisplayName("createSlot")
    class CreateSlot {

        @Test
        @DisplayName("AC-1-10: 不正時刻の作成は400で拒否され_行が増えない")
        void 不正時刻の作成は拒否され行が増えない() {
            // Given: 終了が開始より前（日跨ぎ指定は存在しない）
            CreateShiftSlotRequest req = new CreateShiftSlotRequest(
                    LocalDate.of(2026, 3, 2), LocalTime.of(17, 0), LocalTime.of(9, 0),
                    null, null, null);

            // When & Then
            assertThatThrownBy(() -> shiftSlotService.createSlot(SCHEDULE_ID, req, ACTOR))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftErrorCode.INVALID_TIME_RANGE);

            // 永続化に到達していない＝行が増えない
            verify(slotRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateSlot")
    class UpdateSlot {

        @Test
        @DisplayName("AC-1-11: 時刻を片方だけ更新_もう片方の既存値と合わせて検証される")
        void 片側更新はもう片方の既存値と合わせて検証される() {
            // Given: 既存 09:00-17:00 の枠に対し、開始だけを 18:00 へ動かす（結果 18:00-17:00 で不正）
            given(slotRepository.findById(SLOT_ID))
                    .willReturn(Optional.of(slotEntity(LocalTime.of(9, 0), LocalTime.of(17, 0))));
            UpdateShiftSlotRequest req = new UpdateShiftSlotRequest(
                    null, LocalTime.of(18, 0), null, null, null, null, null);

            // When & Then
            assertThatThrownBy(() -> shiftSlotService.updateSlot(SLOT_ID, req, ACTOR))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ShiftErrorCode.INVALID_TIME_RANGE);

            verify(slotRepository, never()).save(any());
        }

        @Test
        @DisplayName("AC-1-12: 既存の不正時刻行でも_時刻を触らない更新は拒否されない（後方互換）")
        void 時刻を触らない更新は既存の不正時刻を理由に拒否されない() {
            // Given: 既存行が 15 分刻みに乗っていない（09:07-17:00）
            ShiftSlotEntity existing = slotEntity(LocalTime.of(9, 7), LocalTime.of(17, 0));
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.of(existing));
            given(slotRepository.save(any())).willReturn(existing);
            // note のみの更新（時刻は両方 null＝据え置き）
            UpdateShiftSlotRequest req = new UpdateShiftSlotRequest(
                    null, null, null, null, null, null, "メモ更新のみ");

            // When & Then
            assertThatCode(() -> shiftSlotService.updateSlot(SLOT_ID, req, ACTOR))
                    .doesNotThrowAnyException();
            verify(slotRepository).save(any());
            assertThat(existing.getNote()).isEqualTo("メモ更新のみ");
            assertThat(existing.getStartTime()).isEqualTo(LocalTime.of(9, 7));
        }
    }
}
