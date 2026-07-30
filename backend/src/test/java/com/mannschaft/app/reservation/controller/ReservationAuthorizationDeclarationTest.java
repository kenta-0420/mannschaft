package com.mannschaft.app.reservation.controller;

import com.mannschaft.app.reservation.dto.AdminNoteRequest;
import com.mannschaft.app.reservation.dto.BlockedTimeRequest;
import com.mannschaft.app.reservation.dto.BusinessHoursUpdateRequest;
import com.mannschaft.app.reservation.dto.CancelReservationRequest;
import com.mannschaft.app.reservation.dto.CloseSlotRequest;
import com.mannschaft.app.reservation.dto.CreateEmergencyClosureRequest;
import com.mannschaft.app.reservation.dto.CreateReminderRequest;
import com.mannschaft.app.reservation.dto.CreateReservationLineRequest;
import com.mannschaft.app.reservation.dto.CreateSlotRequest;
import com.mannschaft.app.reservation.dto.RescheduleRequest;
import com.mannschaft.app.reservation.dto.UpdateReservationLineRequest;
import com.mannschaft.app.reservation.dto.UpdateReservationSettingRequest;
import com.mannschaft.app.reservation.dto.UpdateSlotRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 予約ドメインの管理系エンドポイントに管理者認可ゲート（{@code @PreAuthorize}）が
 * 宣言されていることを reflection で保証する契約テスト（F03.4 認可漏れ根治）。
 *
 * <p><strong>設計判断（このテスト方式の根拠）:</strong>
 * standaloneSetup / 単体テストでは {@code @PreAuthorize} は強制されないため、
 * 「宣言が存在し正しい SpEL であること」を宣言レベルで保証し、
 * 「その SpEL（{@code isScopeAdmin}）が ADMIN + DEPUTY_ADMIN + SYSTEM_ADMIN のみ許可する強制挙動」は
 * {@code AccessGuardTest} が担う。この 2 つの結合で
 * 「非管理者（MEMBER / 非所属者）は管理操作を実行できない」という受け入れ条件を満たす。
 * 既存の {@code ReservationControllerTest#予約公開設定更新_ADMIN限定宣言} と同じ流儀。</p>
 *
 * <p>管理系ゲートの正準 SpEL: {@value #ADMIN_GATE}（ADMIN + DEPUTY_ADMIN + SYSTEM_ADMIN）。</p>
 */
@DisplayName("予約 管理系エンドポイント 認可宣言テスト")
class ReservationAuthorizationDeclarationTest {

    /** 管理者ゲートの正準 SpEL（ADMIN + DEPUTY_ADMIN + SYSTEM_ADMIN）。 */
    private static final String ADMIN_GATE = "@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')";

    /** 指定メソッドに管理者ゲート（isScopeAdmin）が宣言されていることを検証する。 */
    private static void assertGated(Class<?> controller, String method, Class<?>... params) {
        PreAuthorize pa = annotationOf(controller, method, params);
        assertThat(pa)
                .as("%s#%s に @PreAuthorize（管理者ゲート）が付与されていること", controller.getSimpleName(), method)
                .isNotNull();
        assertThat(pa.value())
                .as("%s#%s のゲートは isScopeAdmin（ADMIN+DEPUTY_ADMIN）であること", controller.getSimpleName(), method)
                .isEqualTo(ADMIN_GATE);
    }

    /** 指定メソッドに認可ゲートが宣言されていないこと（会員/公開フローの開放維持）を検証する。 */
    private static void assertOpen(Class<?> controller, String method, Class<?>... params) {
        PreAuthorize pa = annotationOf(controller, method, params);
        assertThat(pa)
                .as("%s#%s は会員/公開の予約フローが使うため @PreAuthorize を付けてはならない",
                        controller.getSimpleName(), method)
                .isNull();
    }

    private static PreAuthorize annotationOf(Class<?> controller, String method, Class<?>... params) {
        try {
            return controller.getMethod(method, params).getAnnotation(PreAuthorize.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("メソッドが見つかりません: " + controller.getSimpleName() + "#" + method, e);
        }
    }

    // ========================================
    // ゲート対象（管理系）
    // ========================================

    @Nested
    @DisplayName("TeamReservationLineController（ライン管理）")
    class LineGates {
        @Test
        @DisplayName("createLine / updateLine / deleteLine は管理者ゲート")
        void 管理系はゲート済み() {
            assertGated(TeamReservationLineController.class, "createLine",
                    Long.class, CreateReservationLineRequest.class);
            assertGated(TeamReservationLineController.class, "updateLine",
                    Long.class, Long.class, UpdateReservationLineRequest.class);
            assertGated(TeamReservationLineController.class, "deleteLine",
                    Long.class, Long.class);
        }

        @Test
        @DisplayName("listLines（一覧）は開放維持（SlotPicker が予約時に使用）")
        void 一覧は開放() {
            assertOpen(TeamReservationLineController.class, "listLines", Long.class);
        }
    }

    @Nested
    @DisplayName("TeamReservationSlotController（枠管理）")
    class SlotGates {
        @Test
        @DisplayName("createSlot / updateSlot / deleteSlot / closeSlot / reopenSlot は管理者ゲート")
        void 管理系はゲート済み() {
            assertGated(TeamReservationSlotController.class, "createSlot",
                    Long.class, CreateSlotRequest.class);
            assertGated(TeamReservationSlotController.class, "updateSlot",
                    Long.class, Long.class, UpdateSlotRequest.class);
            assertGated(TeamReservationSlotController.class, "deleteSlot",
                    Long.class, Long.class);
            assertGated(TeamReservationSlotController.class, "closeSlot",
                    Long.class, Long.class, CloseSlotRequest.class);
            assertGated(TeamReservationSlotController.class, "reopenSlot",
                    Long.class, Long.class);
        }

        @Test
        @DisplayName("listSlots / listAvailableSlots / getSlot / getGrid は開放維持（予約フローが使用）")
        void 参照は開放() {
            assertOpen(TeamReservationSlotController.class, "listSlots",
                    Long.class, LocalDate.class, LocalDate.class);
            assertOpen(TeamReservationSlotController.class, "listAvailableSlots",
                    Long.class, LocalDate.class, LocalDate.class);
            assertOpen(TeamReservationSlotController.class, "getSlot", Long.class, Long.class);
            // 機能C: 空きグリッドは会員/公開が使う view ゲート（Service 層）のため @PreAuthorize を付けない（C-7）。
            // F03.4.4 で from/to/axis/menuId が増えたシグネチャへ機械的追従（アサーション＝開放維持は不変・H-10）。
            assertOpen(TeamReservationSlotController.class, "getGrid",
                    Long.class, LocalDate.class, LocalDate.class, LocalDate.class,
                    String.class, java.util.UUID.class, java.util.List.class);
        }
    }

    @Nested
    @DisplayName("TeamEmergencyClosureController（臨時休業）")
    class ClosureGates {
        @Test
        @DisplayName("previewClosure / sendClosure / listClosures / getConfirmations は管理者ゲート")
        void 管理系はゲート済み() {
            assertGated(TeamEmergencyClosureController.class, "previewClosure",
                    Long.class, LocalDate.class, LocalDate.class, LocalTime.class, LocalTime.class);
            assertGated(TeamEmergencyClosureController.class, "sendClosure",
                    Long.class, CreateEmergencyClosureRequest.class);
            assertGated(TeamEmergencyClosureController.class, "listClosures", Long.class);
            assertGated(TeamEmergencyClosureController.class, "getConfirmations",
                    Long.class, Long.class);
        }

        @Test
        @DisplayName("confirmClosure（本人確認）は本人レコード必須のため非ゲート")
        void 本人確認は非ゲート() {
            assertOpen(TeamEmergencyClosureController.class, "confirmClosure",
                    Long.class, Long.class);
        }
    }

    @Nested
    @DisplayName("TeamReservationController（予約管理）")
    class ReservationGates {
        @Test
        @DisplayName("一覧/確定/管理却下/完了/ノーショー/リスケ/管理メモ/統計/リマインダーは管理者ゲート")
        void 管理系はゲート済み() {
            assertGated(TeamReservationController.class, "listReservations",
                    Long.class, String.class, int.class, int.class);
            // F03.4.5 §6.2 W2-5: scope（SERIES 一括承認）を additive 追加したためシグネチャが 3 引数になった。
            // 一括承認は「その teamId の管理者」だけが行える必要があるため、ゲートは従来どおり必須。
            assertGated(TeamReservationController.class, "confirmReservation",
                    Long.class, Long.class,
                    com.mannschaft.app.reservation.ReservationConfirmScope.class);
            assertGated(TeamReservationController.class, "cancelReservation",
                    Long.class, Long.class, CancelReservationRequest.class);
            assertGated(TeamReservationController.class, "completeReservation",
                    Long.class, Long.class);
            assertGated(TeamReservationController.class, "markNoShow",
                    Long.class, Long.class);
            assertGated(TeamReservationController.class, "rescheduleReservation",
                    Long.class, Long.class, RescheduleRequest.class);
            assertGated(TeamReservationController.class, "updateAdminNote",
                    Long.class, Long.class, AdminNoteRequest.class);
            assertGated(TeamReservationController.class, "getStats", Long.class);
            assertGated(TeamReservationController.class, "listReminders",
                    Long.class, Long.class);
            assertGated(TeamReservationController.class, "createReminder",
                    Long.class, Long.class, CreateReminderRequest.class);
        }

        @Test
        @DisplayName("createReservation（予約作成）は会員ゲート維持のため非ゲート")
        void 予約作成は非ゲート() {
            assertOpen(TeamReservationController.class, "createReservation",
                    Long.class, com.mannschaft.app.reservation.dto.CreateReservationRequest.class);
        }

        @Test
        @DisplayName("getReservation（単一詳細）は本人閲覧を許すため Service 層で所有権判定（コントローラは非ゲート）")
        void 単一詳細はサービス層ガード() {
            assertOpen(TeamReservationController.class, "getReservation",
                    Long.class, Long.class);
        }
    }

    @Nested
    @DisplayName("ReservationBusinessHourController（営業時間・設定）")
    class BusinessHourGates {
        @Test
        @DisplayName("営業時間更新/ブロック時間 CUD は管理者ゲート")
        void 管理系はゲート済み() {
            assertGated(ReservationBusinessHourController.class, "updateBusinessHours",
                    Long.class, BusinessHoursUpdateRequest.class);
            assertGated(ReservationBusinessHourController.class, "createBlockedTime",
                    Long.class, BlockedTimeRequest.class);
            assertGated(ReservationBusinessHourController.class, "updateBlockedTime",
                    Long.class, Long.class, BlockedTimeRequest.class);
            assertGated(ReservationBusinessHourController.class, "deleteBlockedTime",
                    Long.class, Long.class);
            // 機能B: 予約不可枠 impact プレビューも ADMIN+（副管理者可）ゲート。
            assertGated(ReservationBusinessHourController.class, "getBlockedTimeImpact",
                    Long.class, LocalDate.class,
                    com.mannschaft.app.reservation.ReservationBlockedResourceType.class,
                    Long.class, LocalTime.class, LocalTime.class);
        }

        @Test
        @DisplayName("予約設定更新は isScopeAdmin（副管理者も許可へ揃える）")
        void 設定更新は副管理者許可() {
            assertGated(ReservationBusinessHourController.class, "updateReservationSetting",
                    Long.class, UpdateReservationSettingRequest.class);
        }

        @Test
        @DisplayName("営業時間取得/ブロック時間一覧/設定取得は開放維持（予約フローが使用）")
        void 参照は開放() {
            assertOpen(ReservationBusinessHourController.class, "getBusinessHours", Long.class);
            assertOpen(ReservationBusinessHourController.class, "listBlockedTimes",
                    Long.class, LocalDate.class, LocalDate.class);
            assertOpen(ReservationBusinessHourController.class, "getSettings", Long.class);
        }
    }
}
