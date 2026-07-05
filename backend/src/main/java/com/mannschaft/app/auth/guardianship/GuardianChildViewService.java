package com.mannschaft.app.auth.guardianship;

import com.mannschaft.app.auth.dto.GuardianChildAnnouncementsResponse;
import com.mannschaft.app.auth.dto.GuardianChildMembershipsResponse;
import com.mannschaft.app.auth.dto.GuardianChildProxyActionsResponse;
import com.mannschaft.app.auth.guardianship.GuardianshipSwitchService.SwitchVerdict;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.bulletin.service.BulletinThreadService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import com.mannschaft.app.proxy.dto.ProxyActionView;
import com.mannschaft.app.proxy.service.ProxyInputQueryService;
import com.mannschaft.app.schedule.dto.AttendanceStatsResponse;
import com.mannschaft.app.schedule.dto.CalendarEntryResponse;
import com.mannschaft.app.schedule.service.ScheduleAttendanceService;
import com.mannschaft.app.schedule.service.ScheduleQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * F08.9 件2 保護者による子データ閲覧専用見守りの集約サービス（Guardian Child View）。
 *
 * <p>保護者（親）が <b>12歳未満（小学生以下）の子</b> のデータを <b>閲覧専用</b>で見守るための
 * 読み取り API を担う。「後見切替（acting-as）＝子として操作する」重い経路（P3c）とは別物で、
 * <b>操作せず見るだけ</b>の軽量導線である。書き込み経路は一切持たない（設計書 05 §4.3 / AC-7）。</p>
 *
 * <p>4 面（① 予定 ② 出欠 ③ 所属 ④ お知らせ）＋ 代理履歴（件3）を返す。各面の委譲前に必ず
 * {@link GuardianshipSwitchService#evaluateSwitch}（副作用なし・監査なし）で権原＋年齢ゲートを検証し、
 * {@link SwitchVerdict} を HTTP へ写像する（設計書 05 §4.1）:</p>
 * <ul>
 *   <li>{@code LINK_NOT_FOUND} → 403 {@link MembershipBillingErrorCode#GUARDIANSHIP_LINK_NOT_FOUND}
 *       （他人の子・存在しない子・不整合を一本化＝IDOR 防止・列挙不可）</li>
 *   <li>{@code AGE_LOCKED} → 403 {@link MembershipBillingErrorCode#GUARDIANSHIP_SWITCH_AGE_LOCKED}
 *       （12歳以上の自立段階は封印）</li>
 *   <li>{@code ALLOWED} → 委譲続行（200）</li>
 * </ul>
 *
 * <p><b>ドメイン境界</b>: 他ドメインの Entity を直接参照せず、必ず各ドメインの Service メソッドを
 * ID 経由で呼ぶ（{@link ScheduleQueryService}/{@link ScheduleAttendanceService}/{@link MembershipService}/
 * {@link BulletinThreadService}/{@link ProxyInputQueryService}）。読み取り集約のため
 * {@code @Transactional(readOnly = true)}（{@link GuardianshipSwitchService} と同型・各委譲先が独自 tx を持つ）。</p>
 *
 * <p><b>F00 可視性は子基準</b>: 予定・お知らせはいずれも viewer=childUserId で評価されるため、
 * 保護者だけに見える隠しコンテンツは出ない（設計書 05 §5・意図した挙動）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GuardianChildViewService {

    private final GuardianshipSwitchService guardianshipSwitchService;
    private final ScheduleQueryService scheduleQueryService;
    private final ScheduleAttendanceService scheduleAttendanceService;
    private final MembershipService membershipService;
    private final NameResolverService nameResolverService;
    private final BulletinThreadService bulletinThreadService;
    private final ProxyInputQueryService proxyInputQueryService;

    /** {@link NameResolverService#resolveScopeName} 用の scopeType 文字列。 */
    private static final String SCOPE_TYPE_TEAM = "TEAM";
    private static final String SCOPE_TYPE_ORGANIZATION = "ORGANIZATION";

    /**
     * ① 子の今後の予定（横断カレンダー）を返す。viewer=子基準で F00 可視性が適用される（設計書 05 §5）。
     *
     * @param guardianUserId 保護者（認証ユーザー）のユーザー ID
     * @param childUserId    対象の子のユーザー ID
     * @param from           期間開始
     * @param to             期間終了
     * @return 子が可視なカレンダーエントリ一覧
     * @throws BusinessException 権原なし（403 LINK_NOT_FOUND）／年齢封印（403 AGE_LOCKED）
     */
    public List<CalendarEntryResponse> getChildSchedules(
            Long guardianUserId, Long childUserId, LocalDateTime from, LocalDateTime to) {
        assertGuardianCanView(guardianUserId, childUserId);
        return scheduleQueryService.getMyCalendar(childUserId, from, to);
    }

    /**
     * ② 子の出席率統計を返す。
     *
     * @param guardianUserId 保護者（認証ユーザー）のユーザー ID
     * @param childUserId    対象の子のユーザー ID
     * @param from           期間開始
     * @param to             期間終了
     * @return 子の出席率統計
     * @throws BusinessException 権原なし（403 LINK_NOT_FOUND）／年齢封印（403 AGE_LOCKED）
     */
    public AttendanceStatsResponse getChildAttendanceStats(
            Long guardianUserId, Long childUserId, LocalDateTime from, LocalDateTime to) {
        assertGuardianCanView(guardianUserId, childUserId);
        return scheduleAttendanceService.getMyAttendanceStats(childUserId, from, to);
    }

    /**
     * ③ 子の所属チーム/組織を返す（ID → 名称合成）。
     *
     * @param guardianUserId 保護者（認証ユーザー）のユーザー ID
     * @param childUserId    対象の子のユーザー ID
     * @return 所属チーム・組織（scopeId + name）
     * @throws BusinessException 権原なし（403 LINK_NOT_FOUND）／年齢封印（403 AGE_LOCKED）
     */
    public GuardianChildMembershipsResponse getChildMemberships(Long guardianUserId, Long childUserId) {
        assertGuardianCanView(guardianUserId, childUserId);

        List<GuardianChildMembershipsResponse.ScopeRef> teams =
                membershipService.getActiveTeamIdsByUser(childUserId).stream()
                        .map(teamId -> new GuardianChildMembershipsResponse.ScopeRef(
                                teamId, nameResolverService.resolveScopeName(SCOPE_TYPE_TEAM, teamId)))
                        .toList();
        List<GuardianChildMembershipsResponse.ScopeRef> orgs =
                membershipService.getActiveOrgIdsByUser(childUserId).stream()
                        .map(orgId -> new GuardianChildMembershipsResponse.ScopeRef(
                                orgId, nameResolverService.resolveScopeName(SCOPE_TYPE_ORGANIZATION, orgId)))
                        .toList();
        return new GuardianChildMembershipsResponse(teams, orgs);
    }

    /**
     * ④ 子のお知らせ受信（掲示板スレッド）を全所属スコープ横断で合算し、更新日時降順でページングして返す。
     *
     * <p>子の所属スコープ（チーム/組織）を列挙し、各スコープで
     * {@link BulletinThreadService#listThreads}（viewer=子・所属ゲート）を呼んで合算する。
     * 全体ページングは「各スコープから {@code (page+1)*size} 件を取得 → 合算 → {@code updatedAt} 降順 →
     * ページ窓で切り出し」で近似する（グローバル最新 {@code offset+size} 件を保証。設計書 05 §8）。
     * {@code totalElements} は各スコープの合算件数（1 スレッド＝1 スコープ）。</p>
     *
     * @param guardianUserId 保護者（認証ユーザー）のユーザー ID
     * @param childUserId    対象の子のユーザー ID
     * @param page           ページ番号（0 始まり）
     * @param size           ページサイズ
     * @return お知らせ項目（更新日時降順）とページ情報
     * @throws BusinessException 権原なし（403 LINK_NOT_FOUND）／年齢封印（403 AGE_LOCKED）
     */
    public GuardianChildAnnouncementsResponse getChildAnnouncements(
            Long guardianUserId, Long childUserId, int page, int size) {
        assertGuardianCanView(guardianUserId, childUserId);

        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? 20 : size;
        // 各スコープからグローバル窓（offset+size）を保証するのに十分な件数を取得する。
        int window = (safePage + 1) * safeSize;
        PageRequest perScope = PageRequest.of(0, window);

        List<ThreadResponse> merged = new ArrayList<>();
        long totalElements = 0L;

        for (Long teamId : membershipService.getActiveTeamIdsByUser(childUserId)) {
            Page<ThreadResponse> p =
                    bulletinThreadService.listThreads(ScopeType.TEAM, teamId, childUserId, perScope);
            merged.addAll(p.getContent());
            totalElements += p.getTotalElements();
        }
        for (Long orgId : membershipService.getActiveOrgIdsByUser(childUserId)) {
            Page<ThreadResponse> p =
                    bulletinThreadService.listThreads(ScopeType.ORGANIZATION, orgId, childUserId, perScope);
            merged.addAll(p.getContent());
            totalElements += p.getTotalElements();
        }

        // 更新日時降順（null は最後）でグローバルソート。
        merged.sort(Comparator.comparing(
                ThreadResponse::getUpdatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        int fromIndex = Math.min(safePage * safeSize, merged.size());
        int toIndex = Math.min(fromIndex + safeSize, merged.size());
        List<GuardianChildAnnouncementsResponse.AnnouncementItem> items =
                merged.subList(fromIndex, toIndex).stream()
                        .map(this::toAnnouncementItem)
                        .toList();

        return new GuardianChildAnnouncementsResponse(items, safePage, safeSize, totalElements);
    }

    /**
     * 代理履歴（件3）を返す。{@code proxy_input_records} から subject=子 のレコードのみを新しい順で返す。
     *
     * @param guardianUserId 保護者（認証ユーザー）のユーザー ID
     * @param childUserId    対象の子のユーザー ID
     * @return subject=子 の代理入力履歴（作成日時降順）
     * @throws BusinessException 権原なし（403 LINK_NOT_FOUND）／年齢封印（403 AGE_LOCKED）
     */
    public GuardianChildProxyActionsResponse getChildProxyActions(Long guardianUserId, Long childUserId) {
        assertGuardianCanView(guardianUserId, childUserId);

        List<GuardianChildProxyActionsResponse.ProxyActionItem> items =
                proxyInputQueryService.getActionsBySubject(childUserId).stream()
                        .map(this::toProxyActionItem)
                        .toList();
        return new GuardianChildProxyActionsResponse(items);
    }

    // ========================================
    // ヘルパー
    // ========================================

    /**
     * 保護者が当該子を閲覧できるか（権原＋年齢ゲート）を検証し、不許可なら 403 を投げる。
     * 判定は {@link GuardianshipSwitchService#evaluateSwitch}（副作用なし）を再利用する。
     */
    private void assertGuardianCanView(Long guardianUserId, Long childUserId) {
        SwitchVerdict verdict = guardianshipSwitchService.evaluateSwitch(guardianUserId, childUserId);
        switch (verdict) {
            case LINK_NOT_FOUND -> {
                log.warn("子データ閲覧拒否: 有効な保護者リンクなし guardianUserId={}, childUserId={}",
                        guardianUserId, childUserId);
                throw new BusinessException(MembershipBillingErrorCode.GUARDIANSHIP_LINK_NOT_FOUND);
            }
            case AGE_LOCKED -> {
                log.warn("子データ閲覧拒否: 年齢到達で封印（12歳以上） guardianUserId={}, childUserId={}",
                        guardianUserId, childUserId);
                throw new BusinessException(MembershipBillingErrorCode.GUARDIANSHIP_SWITCH_AGE_LOCKED);
            }
            case ALLOWED -> {
                // 続行
            }
        }
    }

    /** 掲示板スレッド DTO を、閲覧見守り用のお知らせ項目へ縮約する（scopeName を合成）。 */
    private GuardianChildAnnouncementsResponse.AnnouncementItem toAnnouncementItem(ThreadResponse t) {
        String scopeName = nameResolverService.resolveScopeName(t.getScopeType(), t.getScopeId());
        return new GuardianChildAnnouncementsResponse.AnnouncementItem(
                t.getId(), t.getScopeType(), t.getScopeId(), scopeName,
                t.getTitle(), t.getPriority(), t.getCreatedAt(), t.getUpdatedAt());
    }

    /** proxy ドメインの代理入力ビューを、auth 側の代理履歴 DTO へ写像する。subject=子 は自明ゆえ返さない。 */
    private GuardianChildProxyActionsResponse.ProxyActionItem toProxyActionItem(ProxyActionView v) {
        return new GuardianChildProxyActionsResponse.ProxyActionItem(
                v.id(),
                v.proxyUserId(),
                v.featureScope(),
                v.targetEntityType(),
                v.targetEntityId(),
                v.inputSource(),
                v.createdAt());
    }
}
