package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.dto.ShiftScheduleResponse;
import com.mannschaft.app.shift.dto.ShiftSlotResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CMP-260826-2127 AC-5 のうち、<b>DTO の {@code status} が {@code null} の場合の fail-closed</b>
 * を固定する単体テスト（試練 / red 先行）。
 *
 * <p>正本設計: {@code docs/features/F03.5_shift/05_unpublished_visibility.md} §3.4.1 / §7 AC-5。</p>
 *
 * <p><b>なぜ独立したテストが要るか</b>: {@link ShiftPdfService} はエンティティを持たず
 * {@link ShiftScheduleResponse}（DTO）しか受け取らない。この DTO は {@code status} が
 * {@code null} でも組み立てられ、実際に既存の {@code ShiftPdfServiceAuthzTest#scheduleOf}（:65-70）が
 * {@code status} を設定しないまま生成している。{@code status == null} を「公開済み」と解釈すると、
 * DTO を部分的にしか組み立てない経路から遮断がまるごと抜ける。
 * <b>null は未公開扱い（fail-closed）</b>でなければならない。</p>
 *
 * <p>この条件は IT（実 DB 経由）では作れない — エンティティから DTO を組む限り
 * {@code status} は必ず入るためである。よってサービス層のモックテストとして独立させた。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CMP-260826-2127 AC-5: PDF の可視性判定は status==null を未公開扱いにする（試練）")
class ShiftPdfVisibilityFailClosedTest {

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
    private static final Long TEAM_ID = 10L;
    private static final Long REQUESTER = 99L;

    /** 当該チームの一般メンバー（非 SUPPORTER・非 SYSTEM_ADMIN）として認可を通す。 */
    private void givenPlainMember() {
        given(accessControlService.isSystemAdmin(REQUESTER)).willReturn(false);
        given(accessControlService.isMember(REQUESTER, TEAM_ID, "TEAM")).willReturn(true);
        given(accessControlService.isSupporter(REQUESTER, TEAM_ID, "TEAM")).willReturn(false);
        given(accessControlService.isAdminOrAbove(REQUESTER, TEAM_ID, "TEAM")).willReturn(false);
        given(shiftSlotService.listSlots(SCHEDULE_ID, REQUESTER)).willReturn(List.of(
                ShiftSlotResponse.builder().id(1L).scheduleId(SCHEDULE_ID)
                        .assignedUserIds(List.of(REQUESTER)).build()));
    }

    private ShiftScheduleResponse scheduleWithStatus(String status, LocalDateTime publishedAt) {
        return ShiftScheduleResponse.builder()
                .id(SCHEDULE_ID)
                .teamId(TEAM_ID)
                .status(status == null ? null
                        : new ShiftScheduleResponse.ShiftStatusDto(status, publishedAt, null))
                .build();
    }

    @Nested
    @DisplayName("status == null（DTO を部分的にしか組み立てていない経路）")
    class StatusIsNull {

        @Test
        @DisplayName("チームPDF: status が null なら未公開扱いで SHIFT_SCHEDULE_NOT_FOUND")
        void チームPDFはstatusがnullなら404相当() {
            givenPlainMember();
            given(scheduleService.getSchedule(SCHEDULE_ID, REQUESTER))
                    .willReturn(scheduleWithStatus(null, null));

            Throwable thrown = catchThrowable(
                    () -> shiftPdfService.generateTeamPdf(SCHEDULE_ID, REQUESTER));

            assertThat(thrown).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getErrorCode())
                    .isEqualTo((ErrorCode) ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            verify(pdfGeneratorService, never()).generateFromTemplate(any(), any());
        }

        @Test
        @DisplayName("個人PDF: status が null なら未公開扱いで SHIFT_SCHEDULE_NOT_FOUND")
        void 個人PDFはstatusがnullなら404相当() {
            givenPlainMember();
            given(scheduleService.getSchedule(SCHEDULE_ID, REQUESTER))
                    .willReturn(scheduleWithStatus(null, null));

            Throwable thrown = catchThrowable(
                    () -> shiftPdfService.generatePersonalPdf(SCHEDULE_ID, REQUESTER));

            assertThat(thrown).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getErrorCode())
                    .isEqualTo((ErrorCode) ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            verify(pdfGeneratorService, never()).generateFromTemplate(any(), any());
        }
    }

    @Nested
    @DisplayName("publishedAt の非対称な扱い（AC-7 / AC-17 の PDF 経路）")
    class PublishedAtAsymmetry {

        @Test
        @DisplayName("AC-17: PUBLISHED かつ publishedAt が null でも公開扱い（PDF は発行される）")
        void 公開済みはpublishedAtがnullでも発行される() {
            givenPlainMember();
            given(scheduleService.getSchedule(SCHEDULE_ID, REQUESTER))
                    .willReturn(scheduleWithStatus("PUBLISHED", null));
            given(pdfGeneratorService.generateFromTemplate(any(), any()))
                    .willReturn(new byte[]{0x25, 0x50, 0x44, 0x46});

            byte[] result = shiftPdfService.generateTeamPdf(SCHEDULE_ID, REQUESTER);

            assertThat(result).startsWith((byte) 0x25);
        }

        @Test
        @DisplayName("AC-7: ARCHIVED かつ publishedAt が null は未公開扱いで SHIFT_SCHEDULE_NOT_FOUND")
        void アーカイブはpublishedAtがnullなら404相当() {
            givenPlainMember();
            given(scheduleService.getSchedule(SCHEDULE_ID, REQUESTER))
                    .willReturn(scheduleWithStatus("ARCHIVED", null));

            Throwable thrown = catchThrowable(
                    () -> shiftPdfService.generateTeamPdf(SCHEDULE_ID, REQUESTER));

            assertThat(thrown).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getErrorCode())
                    .isEqualTo((ErrorCode) ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            verify(pdfGeneratorService, never()).generateFromTemplate(any(), any());
        }

        @Test
        @DisplayName("ARCHIVED かつ publishedAt ありは公開扱い（PDF は発行される）")
        void アーカイブはpublishedAtがあれば発行される() {
            givenPlainMember();
            given(scheduleService.getSchedule(SCHEDULE_ID, REQUESTER))
                    .willReturn(scheduleWithStatus("ARCHIVED", LocalDateTime.of(2026, 2, 20, 10, 0)));
            given(pdfGeneratorService.generateFromTemplate(any(), any()))
                    .willReturn(new byte[]{0x25, 0x50, 0x44, 0x46});

            byte[] result = shiftPdfService.generateTeamPdf(SCHEDULE_ID, REQUESTER);

            assertThat(result).startsWith((byte) 0x25);
        }
    }
}
