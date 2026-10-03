package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.ShiftScheduleStatus;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * PDF の認可（{@link ShiftPdfFacade}）と tx 本体（{@link ShiftPdfService}）の単体テスト。
 *
 * <p>CMP-260923-0954 W6a: 認可を tx の外の Facade へ移したので、旧 {@code ShiftPdfServiceAuthzTest}
 * （認可根治 Phase 4）の認可の検証をここへ移した（消していない）。検証する認可ルール:</p>
 * <ol>
 *   <li>SUPPORTER は 403（COMMON_002）</li>
 *   <li>越境（所属していない）は不在と同じ 404 SHIFT_001（是正前から。tx 本体の認可に頼らず Facade が畳む）</li>
 *   <li>user_roles のみの ADMIN は是正前から 403（許可を広げない）</li>
 *   <li>メンバー（非 SUPPORTER）は通り、tx 本体へ privileged=false を渡す</li>
 *   <li>SYSTEM_ADMIN は短絡して通り、privileged=true を渡す</li>
 *   <li>未公開は SUPPORTER にも 403 でなく 404（認可結果より先に隠す）</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftPdfFacade 認可・ShiftPdfService tx 本体 単体テスト")
class ShiftPdfFacadeAuthzTest {

    private static final Long SCHEDULE_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long REQUESTER = 99L;

    private static ShiftScheduleScope scope(ShiftScheduleStatus status, LocalDateTime publishedAt) {
        return new ShiftScheduleScope(SCHEDULE_ID, TEAM_ID, status, publishedAt != null);
    }

    private static ShiftScheduleScope publishedScope() {
        return scope(ShiftScheduleStatus.PUBLISHED, LocalDateTime.of(2026, 2, 20, 10, 0));
    }

    private static void assertCode(Throwable thrown, ErrorCode expected) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode()).isEqualTo(expected);
    }

    // ════════════════════════════════════════════════════════════
    // Facade の認可
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ShiftPdfFacade（認可）")
    class FacadeAuthz {

        @Mock
        private ShiftScheduleService scheduleService;
        @Mock
        private ShiftPdfService pdfService;
        @Mock
        private AccessControlService accessControlService;

        @InjectMocks
        private ShiftPdfFacade facade;

        @Test
        @DisplayName("越境（所属していない）は不在と同じ 404 SHIFT_001 で、tx 本体を呼ばない")
        void 越境は404() {
            given(scheduleService.resolveScope(SCHEDULE_ID)).willReturn(publishedScope());
            given(accessControlService.isMember(REQUESTER, TEAM_ID, "TEAM")).willReturn(false);

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> facade.generateTeamPdf(SCHEDULE_ID, REQUESTER));

            assertCode(thrown, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            verifyNoInteractions(pdfService);
        }

        @Test
        @DisplayName("対象不在（resolveScope が SHIFT_001）は tx 本体を呼ばない")
        void 対象不在() {
            given(scheduleService.resolveScope(SCHEDULE_ID))
                    .willThrow(new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND));

            assertThatThrownBy(() -> facade.generatePersonalPdf(SCHEDULE_ID, REQUESTER))
                    .isInstanceOf(BusinessException.class);
            verifyNoInteractions(pdfService, accessControlService);
        }

        @Test
        @DisplayName("SUPPORTER は 403 COMMON_002（個人 PDF も同じ）")
        void SUPPORTERは403() {
            given(scheduleService.resolveScope(SCHEDULE_ID)).willReturn(publishedScope());
            given(accessControlService.isMember(REQUESTER, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isSupporter(REQUESTER, TEAM_ID, "TEAM")).willReturn(true);

            assertCode(org.assertj.core.api.Assertions.catchThrowable(
                    () -> facade.generateTeamPdf(SCHEDULE_ID, REQUESTER)), CommonErrorCode.COMMON_002);
            assertCode(org.assertj.core.api.Assertions.catchThrowable(
                    () -> facade.generatePersonalPdf(SCHEDULE_ID, REQUESTER)), CommonErrorCode.COMMON_002);
            verifyNoInteractions(pdfService);
        }

        @Test
        @DisplayName("未公開は SUPPORTER でも 403 でなく 404（認可結果より先に隠す）")
        void 未公開はSUPPORTERにも404() {
            given(scheduleService.resolveScope(SCHEDULE_ID))
                    .willReturn(scope(ShiftScheduleStatus.DRAFT, null));

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> facade.generateTeamPdf(SCHEDULE_ID, REQUESTER));

            assertCode(thrown, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            verifyNoInteractions(pdfService);
        }

        @Test
        @DisplayName("user_roles のみの ADMIN（在籍なし）は是正前どおり 403（許可を広げない）")
        void userRolesのみのADMINは403() {
            given(scheduleService.resolveScope(SCHEDULE_ID)).willReturn(publishedScope());
            given(accessControlService.isAdminOrAbove(REQUESTER, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isMember(REQUESTER, TEAM_ID, "TEAM")).willReturn(false);

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> facade.generateTeamPdf(SCHEDULE_ID, REQUESTER));

            assertCode(thrown, CommonErrorCode.COMMON_002);
            verifyNoInteractions(pdfService);
        }

        @Test
        @DisplayName("MEMBER（非 SUPPORTER）は通り、tx 本体へ privileged=false を渡す")
        void MEMBERは通る() {
            byte[] expected = {0x25, 0x50, 0x44, 0x46};
            given(scheduleService.resolveScope(SCHEDULE_ID)).willReturn(publishedScope());
            given(accessControlService.isMember(REQUESTER, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isSupporter(REQUESTER, TEAM_ID, "TEAM")).willReturn(false);
            given(pdfService.generateTeamPdf(SCHEDULE_ID, REQUESTER, false)).willReturn(expected);

            assertThat(facade.generateTeamPdf(SCHEDULE_ID, REQUESTER)).isEqualTo(expected);
        }

        @Test
        @DisplayName("在籍する ADMIN は未公開でも通り、privileged=true を渡す")
        void 在籍するADMINは未公開でも通る() {
            byte[] expected = {0x25, 0x50, 0x44, 0x46};
            given(scheduleService.resolveScope(SCHEDULE_ID))
                    .willReturn(scope(ShiftScheduleStatus.DRAFT, null));
            given(accessControlService.isAdminOrAbove(REQUESTER, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isMember(REQUESTER, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isSupporter(REQUESTER, TEAM_ID, "TEAM")).willReturn(false);
            given(pdfService.generatePersonalPdf(SCHEDULE_ID, REQUESTER, true)).willReturn(expected);

            assertThat(facade.generatePersonalPdf(SCHEDULE_ID, REQUESTER)).isEqualTo(expected);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN は短絡して通り、チーム判定を撃たず privileged=true を渡す")
        void SYSTEM_ADMINは短絡で通る() {
            byte[] expected = {0x25, 0x50, 0x44, 0x46};
            given(scheduleService.resolveScope(SCHEDULE_ID))
                    .willReturn(scope(ShiftScheduleStatus.DRAFT, null));
            given(accessControlService.isSystemAdmin(REQUESTER)).willReturn(true);
            given(pdfService.generateTeamPdf(SCHEDULE_ID, REQUESTER, true)).willReturn(expected);

            assertThat(facade.generateTeamPdf(SCHEDULE_ID, REQUESTER)).isEqualTo(expected);
            verify(accessControlService, never()).isMember(anyLong(), anyLong(), any());
            verify(accessControlService, never()).isAdminOrAbove(anyLong(), anyLong(), any());
        }
    }

    // ════════════════════════════════════════════════════════════
    // tx 本体
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ShiftPdfService（tx 本体。認可なし）")
    class TxBody {

        @Mock
        private ShiftScheduleService scheduleService;
        @Mock
        private ShiftSlotService shiftSlotService;
        @Mock
        private PdfGeneratorService pdfGeneratorService;

        @InjectMocks
        private ShiftPdfService pdfService;

        private ShiftScheduleResponse publishedSchedule() {
            return ShiftScheduleResponse.builder()
                    .id(SCHEDULE_ID)
                    .teamId(TEAM_ID)
                    .status(new ShiftScheduleResponse.ShiftStatusDto(
                            "PUBLISHED", LocalDateTime.of(2026, 2, 20, 10, 0), null))
                    .build();
        }

        private List<ShiftSlotResponse> slots() {
            return List.of(
                    ShiftSlotResponse.builder().id(1L).scheduleId(SCHEDULE_ID)
                            .assignedUserIds(List.of(REQUESTER)).build(),
                    ShiftSlotResponse.builder().id(2L).scheduleId(SCHEDULE_ID)
                            .assignedUserIds(List.of(7L)).build());
        }

        @Test
        @DisplayName("チーム PDF: tx の中でスケジュール・枠を読み直し、テンプレートから PDF を作る")
        void チームPDFを生成する() {
            byte[] expected = {0x25, 0x50, 0x44, 0x46};
            given(scheduleService.getSchedule(SCHEDULE_ID, false)).willReturn(publishedSchedule());
            given(shiftSlotService.listSlots(SCHEDULE_ID, false)).willReturn(slots());
            given(pdfGeneratorService.generateFromTemplate(eq("pdf/shift-team"), any())).willReturn(expected);

            assertThat(pdfService.generateTeamPdf(SCHEDULE_ID, REQUESTER, false)).isEqualTo(expected);
        }

        @Test
        @DisplayName("個人 PDF: 自分の割当の枠だけに絞る")
        void 個人PDFは自分の枠だけに絞る() {
            byte[] expected = {0x25, 0x50, 0x44, 0x46};
            given(scheduleService.getSchedule(SCHEDULE_ID, false)).willReturn(publishedSchedule());
            given(shiftSlotService.listSlots(SCHEDULE_ID, false)).willReturn(slots());
            given(pdfGeneratorService.generateFromTemplate(eq("pdf/shift-personal"), any())).willAnswer(inv -> {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> variables = inv.getArgument(1);
                @SuppressWarnings("unchecked")
                List<ShiftSlotResponse> mine = (List<ShiftSlotResponse>) variables.get("slots");
                assertThat(mine).extracting(ShiftSlotResponse::getId).containsExactly(1L);
                return expected;
            });

            assertThat(pdfService.generatePersonalPdf(SCHEDULE_ID, REQUESTER, false)).isEqualTo(expected);
        }

        @Test
        @DisplayName("不在・削除済み（getSchedule が SHIFT_001）は PDF を作らない")
        void 不在は生成しない() {
            given(scheduleService.getSchedule(SCHEDULE_ID, true))
                    .willThrow(new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND));

            assertThatThrownBy(() -> pdfService.generateTeamPdf(SCHEDULE_ID, REQUESTER, true))
                    .isInstanceOf(BusinessException.class);
            verify(pdfGeneratorService, never()).generateFromTemplate(any(), any());
            verify(shiftSlotService, never()).listSlots(anyLong(), anyBoolean());
        }
    }
}
