package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.shift.ShiftErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * シフト PDF の <b>認可ファサード</b>（トランザクションの外）。
 *
 * <p>CMP-260923-0954 W6a: 認可を {@code @Transactional} の外へ出す。本クラスは {@code @Transactional} を
 * <b>付けない</b>。流れは「{@link ShiftScheduleService#resolveScope} の readOnly な scope 解決 → 認可 →
 * {@link ShiftPdfService} の tx 本体（中でスケジュール・枠を読み直し、公開状態を再判定する）」。</p>
 *
 * <p>是正前は、越境の 404 隠蔽を {@code ShiftScheduleService#getSchedule} の認可に頼っていた
 *（{@code ShiftPdfService} 側の認可は 403）。tx 本体から認可を抜くと PDF が新しい存在オラクルになるため、
 * 同じ判定順（未公開の 404 → 越境の 404 → SUPPORTER・非在籍の 403）をここへ移した。</p>
 *
 * <h3>認可表（是正前の契約から変えていない）</h3>
 * <pre>
 * 主体                          応答
 * 部外者(越境)                  404 SHIFT_001（不在 ID と同一）
 * 同チーム SUPPORTER            403 COMMON_002（未公開なら先に 404）
 * 同チーム一般メンバー          200（公開済みのみ。COLLECTING・ADJUSTING・未公開は 404 SHIFT_001）
 * チーム ADMIN（在籍あり）      200（未公開も可）
 * user_roles のみの ADMIN       403 COMMON_002（是正前から在籍を要求。許可は広げない）
 * SYSTEM_ADMIN(非/メンバー)     200
 * 対象不在・削除済み            404 SHIFT_001
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class ShiftPdfFacade {

    private static final String TEAM = "TEAM";

    private final ShiftScheduleService scheduleService;
    private final ShiftPdfService pdfService;
    private final AccessControlService accessControlService;

    /**
     * チーム全体表 PDF を生成する。
     *
     * @param scheduleId  スケジュール ID
     * @param requesterId リクエスターのユーザー ID
     * @return PDF の byte[]
     */
    public byte[] generateTeamPdf(Long scheduleId, Long requesterId) {
        boolean privileged = authorize(scheduleId, requesterId);
        return pdfService.generateTeamPdf(scheduleId, requesterId, privileged);
    }

    /**
     * 個人タイムライン PDF を生成する。
     *
     * @param scheduleId  スケジュール ID
     * @param requesterId リクエスターのユーザー ID（個人フィルタ用）
     * @return PDF の byte[]
     */
    public byte[] generatePersonalPdf(Long scheduleId, Long requesterId) {
        boolean privileged = authorize(scheduleId, requesterId);
        return pdfService.generatePersonalPdf(scheduleId, requesterId, privileged);
    }

    /**
     * PDF 発行の認可。メンバー（MEMBER 以上、SUPPORTER は不可）のみ。SYSTEM_ADMIN は常に可。
     *
     * @return 管理者側（SYSTEM_ADMIN または ADMIN 以上）なら true（未公開でも発行できる）
     */
    private boolean authorize(Long scheduleId, Long requesterId) {
        ShiftScheduleScope scope = scheduleService.resolveScope(scheduleId);
        if (accessControlService.isSystemAdmin(requesterId)) {
            return true;
        }
        boolean privileged = accessControlService.isAdminOrAbove(requesterId, scope.teamId(), TEAM);
        if (!privileged) {
            // 未公開は認可結果より先に 404、次に越境（所属していない）も不在と同じ 404。
            if (scope.isHidden()) {
                throw new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            }
            if (!accessControlService.isMember(requesterId, scope.teamId(), TEAM)) {
                throw new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            }
        } else if (!accessControlService.isMember(requesterId, scope.teamId(), TEAM)) {
            // user_roles のみの管理者: PDF は是正前から在籍を要求して 403（許可を広げない）。
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        // SUPPORTER は閲覧者ロールで、PDF のような運用情報へは出せない（IDOR 防止も兼ねる）。
        if (accessControlService.isSupporter(requesterId, scope.teamId(), TEAM)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return privileged;
    }
}
