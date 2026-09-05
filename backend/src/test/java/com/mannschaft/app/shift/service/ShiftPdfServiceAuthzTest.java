package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.shift.dto.ShiftScheduleResponse;
import com.mannschaft.app.shift.dto.ShiftSlotResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftPdfService} の認可ロジック（生穴封鎖）単体テスト（認可根治 Phase 4）。
 *
 * <p>検証対象の認可ルール:
 * <ol>
 *   <li>SUPPORTER は {@link BusinessException}（COMMON_002 相当の 403）</li>
 *   <li>非メンバーは {@link BusinessException}（403 相当）</li>
 *   <li>MEMBER（非 SUPPORTER）は正常通過</li>
 *   <li>他チームの scheduleId による IDOR は teamId ミスマッチで弾く</li>
 *   <li>SYSTEM_ADMIN は全スコープ通過（短絡）</li>
 * </ol>
 * </p>
 *
 * <p>{@link ShiftPdfService#checkMemberAndNotSupporter} は private メソッドのため、
 * 公開エントリポイント {@code generateTeamPdf} / {@code generatePersonalPdf} 経由でテストする。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftPdfService 認可（生穴封鎖・Phase 4）単体テスト")
class ShiftPdfServiceAuthzTest {

    @Mock
    private ShiftScheduleService scheduleService;
    @Mock
    private ShiftSlotService shiftSlotService;
    @Mock
    private PdfGeneratorService pdfGeneratorService;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private ShiftPdfService shiftPdfService;

    private static final Long SCHEDULE_ID = 1L;
    private static final Long TEAM_ID     = 10L;
    private static final Long REQUESTER   = 99L;
    private static final Long OTHER_TEAM  = 20L;

    /**
     * テスト用の ShiftScheduleResponse（teamId = TEAM_ID）を返す。
     *
     * <p>CMP-260826-2127（AC-13）: 本フィクスチャはかつて {@code status} を設定しておらず、
     * 未公開シフト表の遮断を入れると fail-closed で 404 になってしまっていた。
     * 本クラスが検証したいのは<b>認可（誰が PDF を取れるか）</b>であり可視性ではないため、
     * 期待値ではなくフィクスチャ側を「日常の正常系＝公開済みシフト表」に直してある。</p>
     */
    private ShiftScheduleResponse scheduleOf(Long teamId) {
        return ShiftScheduleResponse.builder()
                .id(SCHEDULE_ID)
                .teamId(teamId)
                .status(new ShiftScheduleResponse.ShiftStatusDto(
                        "PUBLISHED", LocalDateTime.of(2026, 2, 20, 10, 0), null))
                .build();
    }

    /** テスト用の SlotList（非空）を返す。 */
    private List<ShiftSlotResponse> sampleSlots() {
        return List.of(
                ShiftSlotResponse.builder()
                        .id(1L)
                        .scheduleId(SCHEDULE_ID)
                        .assignedUserIds(List.of(REQUESTER))
                        .build()
        );
    }

    // ════════════════════════════════════════════════════════════
    // generateTeamPdf
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("generateTeamPdf")
    class GenerateTeamPdf {

        @Test
        @DisplayName("非メンバーは COMMON_002（isMember=false で BusinessException）")
        void 非メンバー_COMMON_002() {
            // isMember が false → 最初のチェックで弾かれる
            given(scheduleService.getSchedule(SCHEDULE_ID, REQUESTER)).willReturn(scheduleOf(TEAM_ID));
            given(accessControlService.isMember(REQUESTER, TEAM_ID, "TEAM")).willReturn(false);

            assertThatThrownBy(() -> shiftPdfService.generateTeamPdf(SCHEDULE_ID, REQUESTER))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(CommonErrorCode.COMMON_002.getMessage());

            // 認可で弾かれたので PDF 生成は呼ばれない
            verify(pdfGeneratorService, never()).generateFromTemplate(any(), any());
        }

        @Test
        @DisplayName("SUPPORTER は COMMON_002（isMember=true / isSupporter=true で BusinessException）")
        void SUPPORTER_COMMON_002() {
            // メンバーだが SUPPORTER ロール → 二番目のチェックで弾かれる
            given(scheduleService.getSchedule(SCHEDULE_ID, REQUESTER)).willReturn(scheduleOf(TEAM_ID));
            given(accessControlService.isMember(REQUESTER, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isSupporter(REQUESTER, TEAM_ID, "TEAM")).willReturn(true);

            assertThatThrownBy(() -> shiftPdfService.generateTeamPdf(SCHEDULE_ID, REQUESTER))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(CommonErrorCode.COMMON_002.getMessage());

            verify(pdfGeneratorService, never()).generateFromTemplate(any(), any());
        }

        @Test
        @DisplayName("MEMBER（非 SUPPORTER）は認可通過し PDF バイト列を返す")
        void MEMBER_認可通過() {
            given(scheduleService.getSchedule(SCHEDULE_ID, REQUESTER)).willReturn(scheduleOf(TEAM_ID));
            given(accessControlService.isMember(REQUESTER, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isSupporter(REQUESTER, TEAM_ID, "TEAM")).willReturn(false);
            given(shiftSlotService.listSlots(SCHEDULE_ID, REQUESTER)).willReturn(sampleSlots());
            byte[] expected = new byte[]{0x25, 0x50, 0x44, 0x46}; // "%PDF" マジックバイト
            given(pdfGeneratorService.generateFromTemplate(eq("pdf/shift-team"), any()))
                    .willReturn(expected);

            byte[] result = shiftPdfService.generateTeamPdf(SCHEDULE_ID, REQUESTER);

            assertThat(result).isEqualTo(expected);
            verify(pdfGeneratorService).generateFromTemplate(eq("pdf/shift-team"), any());
        }

        @Test
        @DisplayName("他チームの scheduleId による IDOR は teamId 解決後に non-member 扱いで COMMON_002")
        void IDOR_他チームのscheduleId_COMMON_002() {
            // scheduleId=1 が OTHER_TEAM に紐づく。REQUESTER は TEAM_ID のメンバーだが OTHER_TEAM では非メンバー
            given(scheduleService.getSchedule(SCHEDULE_ID, REQUESTER)).willReturn(scheduleOf(OTHER_TEAM));
            given(accessControlService.isMember(REQUESTER, OTHER_TEAM, "TEAM")).willReturn(false);

            assertThatThrownBy(() -> shiftPdfService.generateTeamPdf(SCHEDULE_ID, REQUESTER))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(CommonErrorCode.COMMON_002.getMessage());

            // 解決した teamId が OTHER_TEAM になることを確認（TEAM_ID への呼び出しはゼロ）
            verify(accessControlService).isMember(REQUESTER, OTHER_TEAM, "TEAM");
            verify(accessControlService, never()).isMember(REQUESTER, TEAM_ID, "TEAM");
        }
    }

    // ════════════════════════════════════════════════════════════
    // generatePersonalPdf
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("generatePersonalPdf")
    class GeneratePersonalPdf {

        @Test
        @DisplayName("SUPPORTER は COMMON_002（generatePersonalPdf でも同じ認可ルール）")
        void SUPPORTER_personalPdf_COMMON_002() {
            given(scheduleService.getSchedule(SCHEDULE_ID, REQUESTER)).willReturn(scheduleOf(TEAM_ID));
            given(accessControlService.isMember(REQUESTER, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isSupporter(REQUESTER, TEAM_ID, "TEAM")).willReturn(true);

            assertThatThrownBy(() -> shiftPdfService.generatePersonalPdf(SCHEDULE_ID, REQUESTER))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(CommonErrorCode.COMMON_002.getMessage());

            verify(pdfGeneratorService, never()).generateFromTemplate(any(), any());
        }

        @Test
        @DisplayName("MEMBER（非 SUPPORTER）は個人スロットのみフィルタして PDF バイト列を返す")
        void MEMBER_personalPdf_認可通過() {
            given(scheduleService.getSchedule(SCHEDULE_ID, REQUESTER)).willReturn(scheduleOf(TEAM_ID));
            given(accessControlService.isMember(REQUESTER, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isSupporter(REQUESTER, TEAM_ID, "TEAM")).willReturn(false);
            given(shiftSlotService.listSlots(SCHEDULE_ID, REQUESTER)).willReturn(sampleSlots());
            byte[] expected = new byte[]{0x25, 0x50, 0x44, 0x46};
            given(pdfGeneratorService.generateFromTemplate(eq("pdf/shift-personal"), any()))
                    .willReturn(expected);

            byte[] result = shiftPdfService.generatePersonalPdf(SCHEDULE_ID, REQUESTER);

            assertThat(result).isEqualTo(expected);
            verify(pdfGeneratorService).generateFromTemplate(eq("pdf/shift-personal"), any());
        }
    }
}
