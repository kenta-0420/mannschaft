package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ScopeConcealingAccessGate;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.dto.CreateShiftScheduleRequest;
import com.mannschaft.app.shift.dto.ManualRemindResponse;
import com.mannschaft.app.shift.dto.ShiftScheduleResponse;
import com.mannschaft.app.shift.dto.ShiftScheduleSummaryResponse;
import com.mannschaft.app.shift.dto.UpdateShiftScheduleRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * シフトスケジュールの <b>認可ファサード</b>（トランザクションの外）。手動リマインドもここに置く。
 *
 * <p>CMP-260923-0954 W6a: 認可（{@code AccessControlService} / {@code ScopeConcealingAccessGate}）を
 * {@code @Transactional} の外へ出す。本クラスは {@code @Transactional} を<b>付けない</b>（クラスにもメソッドにも）。
 * 流れは「{@link ShiftScheduleService#resolveScope} の readOnly な scope 解決（自ドメインのみ・FOR UPDATE なし）→
 * 認可 → {@link ShiftScheduleService} / {@link ShiftPreferenceReminderBatchService} の tx 本体（中で読み直す）」。
 * Controller は本クラスだけを呼ぶ。名前を {@code *AccessService} / {@code *AccessGate} にしないのは、
 * 呼んだだけで認可シグナル扱いになり本クラス内の認可漏れを AuthzControllerGuard が見逃すのを避けるため。</p>
 *
 * <h3>認可表（是正前の契約から変えていない。ただし remind の越境は不在と同じ 404 へ揃えた）</h3>
 * <pre>
 * 主体＼EP                  一覧(teamId)   詳細(id)         作成(teamId)    更新/削除/遷移/サマリ/複製/remind(id)
 * 部外者(越境)              403 COMMON_002 404 SHIFT_001    403 COMMON_002  404 SHIFT_001（remind も。是正前は 403）
 * 同チーム SUPPORTER        403 COMMON_002 403 COMMON_002   403 COMMON_002  403 COMMON_002
 * 同チーム一般メンバー      200            200              403 COMMON_002  403 COMMON_002
 * チーム ADMIN/DEPUTY_ADMIN 200(全件)      200              201             200/204/201
 * user_roles のみの ADMIN   403 COMMON_002 200              201             200/204/201（一覧は是正前から在籍を要求。広げない）
 * SYSTEM_ADMIN(非/メンバー) 200(全件)      200              201             200/204/201
 * 対象不在・親削除済み      (n/a)          404 SHIFT_001    (n/a)           404 SHIFT_001
 * </pre>
 * <p>未公開（DRAFT・ARCHIVED で publishedAt なし）の詳細は、管理者側以外へは認可結果より先に 404 SHIFT_001 へ
 * 寄せる（存在オラクル対策）。一覧・作成は {@code ?teamId=} 直接指定なので越境も不在も 403 のまま（隠さない）。</p>
 */
@Service
@RequiredArgsConstructor
public class ShiftScheduleFacade {

    private static final String TEAM = "TEAM";

    private final ShiftScheduleService scheduleService;
    private final ShiftPreferenceReminderBatchService reminderService;
    private final ScopeConcealingAccessGate accessGate;
    private final AccessControlService accessControlService;

    /**
     * チームのシフトスケジュール一覧を取得する（期間指定は {@code from}・{@code to} が両方あるとき）。
     *
     * <p>SYSTEM_ADMIN、または当該チームの非 SUPPORTER メンバーのみ。それ以外は 403（{@code COMMON_002}）。
     * 管理者側（SYSTEM_ADMIN・ADMIN 以上）は未公開も含めて全量、一般メンバーは公開済みのみ。</p>
     *
     * @param teamId チームID
     * @param from   期間開始（null なら期間指定なし）
     * @param to     期間終了（null なら期間指定なし）
     * @param userId 操作者ユーザーID
     * @return シフトスケジュール一覧
     */
    public List<ShiftScheduleResponse> listSchedules(Long teamId, LocalDate from, LocalDate to, Long userId) {
        boolean systemAdmin = accessControlService.isSystemAdmin(userId);
        if (!systemAdmin) {
            if (!accessControlService.isMember(userId, teamId, TEAM)
                    || accessControlService.isSupporter(userId, teamId, TEAM)) {
                throw new BusinessException(CommonErrorCode.COMMON_002);
            }
        }
        boolean privileged = systemAdmin || accessControlService.isAdminOrAbove(userId, teamId, TEAM);
        if (from != null && to != null) {
            return scheduleService.listSchedulesByPeriod(teamId, from, to, privileged);
        }
        return scheduleService.listSchedules(teamId, privileged);
    }

    /**
     * シフトスケジュールを単体取得する。
     *
     * <p>管理者側（SYSTEM_ADMIN・ADMIN 以上。user_roles のみの ADMIN も含む）は常に可。それ以外は、未公開なら
     * 404（認可結果より先）、所属していなければ越境として 404、SUPPORTER は 403。</p>
     *
     * @param id     スケジュールID
     * @param userId 操作者ユーザーID
     * @return シフトスケジュール
     */
    public ShiftScheduleResponse getSchedule(Long id, Long userId) {
        ShiftScheduleScope scope = scheduleService.resolveScope(id);
        boolean privileged = isPrivileged(scope.teamId(), userId);
        if (!privileged) {
            if (scope.isHidden()) {
                throw new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            }
            if (!accessControlService.isMember(userId, scope.teamId(), TEAM)) {
                throw new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            }
            if (accessControlService.isSupporter(userId, scope.teamId(), TEAM)) {
                throw new BusinessException(CommonErrorCode.COMMON_002);
            }
        }
        return scheduleService.getSchedule(id, privileged);
    }

    /**
     * シフトスケジュールを作成する（SYSTEM_ADMIN、または当該チームの ADMIN/DEPUTY_ADMIN）。
     *
     * <p>{@code ?teamId=} 直接指定のため、越境・不在・権限不足はいずれも 403（{@code COMMON_002}）のまま。</p>
     *
     * @param teamId チームID
     * @param req    作成リクエスト
     * @param userId 作成者ID
     * @return 作成されたシフトスケジュール
     */
    public ShiftScheduleResponse createSchedule(Long teamId, CreateShiftScheduleRequest req, Long userId) {
        if (!accessControlService.isSystemAdmin(userId)) {
            accessControlService.checkAdminOrAbove(userId, teamId, TEAM);
        }
        return scheduleService.createSchedule(teamId, req, userId);
    }

    /**
     * シフトスケジュールを更新する（SYSTEM_ADMIN、または当該チームの ADMIN/DEPUTY_ADMIN）。
     *
     * @param id     スケジュールID
     * @param req    更新リクエスト
     * @param userId 操作者ユーザーID
     * @return 更新されたシフトスケジュール
     */
    public ShiftScheduleResponse updateSchedule(Long id, UpdateShiftScheduleRequest req, Long userId) {
        requireAdmin(scheduleService.resolveScope(id), userId);
        return scheduleService.updateSchedule(id, req);
    }

    /**
     * シフトスケジュールを論理削除する（SYSTEM_ADMIN、または当該チームの ADMIN/DEPUTY_ADMIN）。
     *
     * <p>親行の FOR UPDATE は認可の後（tx 本体の中）で取る。部外者は他チームの行を掴めない。</p>
     *
     * @param id     スケジュールID
     * @param userId 操作者ユーザーID
     */
    public void deleteSchedule(Long id, Long userId) {
        requireAdmin(scheduleService.resolveScope(id), userId);
        scheduleService.deleteSchedule(id, userId);
    }

    /**
     * シフトスケジュールのステータスを遷移する（SYSTEM_ADMIN、または当該チームの ADMIN/DEPUTY_ADMIN）。
     *
     * @param id     スケジュールID
     * @param status 遷移先ステータス
     * @param userId 操作者ID
     * @return 更新されたシフトスケジュール
     */
    public ShiftScheduleResponse transitionStatus(Long id, String status, Long userId) {
        requireAdmin(scheduleService.resolveScope(id), userId);
        return scheduleService.transitionStatus(id, status, userId);
    }

    /**
     * 充足状況サマリーを取得する（SYSTEM_ADMIN、または当該チームの ADMIN/DEPUTY_ADMIN）。
     *
     * @param id     スケジュールID
     * @param userId 操作者ユーザーID
     * @return 日付別・ポジション別の充足状況サマリー
     */
    public ShiftScheduleSummaryResponse getScheduleSummary(Long id, Long userId) {
        requireAdmin(scheduleService.resolveScope(id), userId);
        return scheduleService.getScheduleSummary(id);
    }

    /**
     * 未提出メンバーへ手動でリマインド通知を送る（SYSTEM_ADMIN、または当該チームの ADMIN/DEPUTY_ADMIN。COLLECTING のみ）。
     *
     * <p>越境は不在と同じ 404（{@code SHIFT_001}）。是正前は実在の他チーム ID が 403、不在 ID が 404 で
     * 割れていた（存在オラクル）。Valkey の連打防止ロックは認可の後（tx 本体の先頭）で取るので、
     * 部外者は管理者の手動リマインドを塞げない。</p>
     *
     * @param id     スケジュールID
     * @param userId 操作した管理者の ID
     * @return 送信件数と対象ユーザー ID 一覧
     */
    public ManualRemindResponse remindUnsubmitted(Long id, Long userId) {
        requireAdmin(scheduleService.resolveScope(id), userId);
        return reminderService.triggerManualReminder(id, userId);
    }

    /**
     * シフトスケジュールを複製する（複製元の所属チームの SYSTEM_ADMIN、または ADMIN/DEPUTY_ADMIN）。
     *
     * @param id     複製元ID
     * @param userId 作成者ID
     * @return 複製されたシフトスケジュール
     */
    public ShiftScheduleResponse duplicateSchedule(Long id, Long userId) {
        requireAdmin(scheduleService.resolveScope(id), userId);
        return scheduleService.duplicateSchedule(id, userId);
    }

    /**
     * 管理操作の認可。越境は不在と同一の {@code SHIFT_001}、同一チーム内の権限不足は 403。
     *
     * @param scope  認可の前に解決した scope（スケジュール実体由来の teamId）
     * @param userId 操作者
     */
    private void requireAdmin(ShiftScheduleScope scope, Long userId) {
        accessGate.requireAdminOrConceal(
                userId, scope.teamId(), TEAM, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
    }

    /**
     * 管理者側（未公開も全量見てよい立場）かを判定する。SYSTEM_ADMIN を最初に短絡し、次に
     * user_roles と memberships の 2 系統を統合した {@code isAdminOrAbove} で判定する
     *（user_roles にだけ ADMIN を持つ利用者を越境と誤判定しない）。
     */
    private boolean isPrivileged(Long teamId, Long userId) {
        return accessControlService.isSystemAdmin(userId)
                || accessControlService.isAdminOrAbove(userId, teamId, TEAM);
    }
}
