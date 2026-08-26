package com.mannschaft.app.facility.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.facility.FacilityErrorCode;
import com.mannschaft.app.facility.entity.FacilityBookingEntity;
import com.mannschaft.app.facility.entity.SharedFacilityEntity;
import com.mannschaft.app.facility.repository.FacilityBookingRepository;
import com.mannschaft.app.facility.repository.SharedFacilityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 施設ドメインの認可ガード（認可根治戦役 Wave5 早馬）。
 *
 * <p>本ドメインは全 EP で認可を必要とする（bookingId/facilityId で他組織・他チームの予約・施設に
 * read/承認/取消/削除が及ぶ操作を含むため）。全 Controller の
 * public 入口でここを経由して認可を敷く（{@code feedback_authz_gate_on_public_entry_not_shared_method}:
 * 認可ガードは public 入口に集約し、バッチ等と共有される内部 finder には敷かない）。</p>
 *
 * <h3>BOLA 根治の要 — entity 由来スコープで認可する</h3>
 * <ul>
 *   <li>施設系: {@code findByIdAndScopeTypeAndScopeId} で「施設が実在するスコープ」と URL パスの
 *       scope が一致することを保証し、食い違えば {@code FACILITY_001}（404）で存在秘匿する。</li>
 *   <li>予約系: booking → facilityId → {@link SharedFacilityEntity} の scopeType/scopeId を辿り、
 *       その entity の scope が URL パスの scope と食い違えば {@code FACILITY_006}（404）で存在秘匿する。
 *       Controller のパス変数（organizationId/teamId）を鵜呑みにしない。</li>
 * </ul>
 *
 * <h3>粒度</h3>
 * <ul>
 *   <li>read（一覧/詳細/カレンダー/空き状況/ルール・料金・備品の参照/支払い参照）=
 *       {@link AccessControlService#checkMembership}（非メンバー 403 / 越境 ID 404）。</li>
 *   <li>write（作成/更新/削除/承認/却下/チェックイン/完了/設定更新/支払い確認）=
 *       {@link AccessControlService#checkAdminOrAbove}（非 ADMIN 403 / 越境 ID 404）。</li>
 *   <li>本人操作を許容する予約の更新・キャンセルのみ
 *       {@link AccessControlService#checkOwnerOrAdmin}（予約者本人 or ADMIN）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FacilityAccessGuard {

    private final SharedFacilityRepository facilityRepository;
    private final FacilityBookingRepository bookingRepository;
    private final AccessControlService accessControlService;

    // ════════════════════════════════════════════════════════════════════
    // スコープ宣言型の入口（一覧/作成/設定/統計/カレンダー）
    //   URL パスが scope を明示している。scope 自体は秘匿不要（非メンバーは 403）。
    // ════════════════════════════════════════════════════════════════════

    /** 指定スコープのメンバーであることを要求する（read）。非メンバーは 403。 */
    public void requireScopeMember(String scopeType, Long scopeId, Long userId) {
        accessControlService.checkMembership(userId, scopeId, scopeType);
    }

    /** 指定スコープの ADMIN/DEPUTY_ADMIN であることを要求する（write）。違反は 403。 */
    public void requireScopeAdmin(String scopeType, Long scopeId, Long userId) {
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType);
    }

    // ════════════════════════════════════════════════════════════════════
    // 施設スコープの入口（施設詳細/更新/削除/空き状況/ルール・料金・備品/予約作成）
    //   entity 由来 scope で認可。越境 facilityId は 404（FACILITY_001）で存在秘匿。
    // ════════════════════════════════════════════════════════════════════

    /** 施設が実在するスコープ（entity 由来）のメンバーであることを要求する（read）。越境は 404。 */
    public void requireFacilityMember(String scopeType, Long scopeId, Long facilityId, Long userId) {
        SharedFacilityEntity facility = resolveFacilityInScope(scopeType, scopeId, facilityId);
        accessControlService.checkMembership(userId, facility.getScopeId(), facility.getScopeType());
    }

    /** 施設が実在するスコープ（entity 由来）の ADMIN/DEPUTY_ADMIN を要求する（write）。越境は 404、非 ADMIN は 403。 */
    public void requireFacilityAdmin(String scopeType, Long scopeId, Long facilityId, Long userId) {
        SharedFacilityEntity facility = resolveFacilityInScope(scopeType, scopeId, facilityId);
        accessControlService.checkAdminOrAbove(userId, facility.getScopeId(), facility.getScopeType());
    }

    // ════════════════════════════════════════════════════════════════════
    // 予約スコープの入口（予約詳細/更新/取消/承認/却下/チェックイン/完了/PDF/支払い）
    //   booking → facility → scope を辿り、越境 bookingId は 404（FACILITY_006）で存在秘匿。
    // ════════════════════════════════════════════════════════════════════

    /** 予約の属する施設スコープ（entity 由来）のメンバーを要求する（read）。越境は 404。 */
    public void requireBookingMember(String scopeType, Long scopeId, Long bookingId, Long userId) {
        SharedFacilityEntity facility = resolveBookingFacilityInScope(scopeType, scopeId, bookingId);
        accessControlService.checkMembership(userId, facility.getScopeId(), facility.getScopeType());
    }

    /** 予約の属する施設スコープ（entity 由来）の ADMIN/DEPUTY_ADMIN を要求する（write）。越境は 404、非 ADMIN は 403。 */
    public void requireBookingAdmin(String scopeType, Long scopeId, Long bookingId, Long userId) {
        SharedFacilityEntity facility = resolveBookingFacilityInScope(scopeType, scopeId, bookingId);
        accessControlService.checkAdminOrAbove(userId, facility.getScopeId(), facility.getScopeType());
    }

    /**
     * 予約者本人または施設スコープの ADMIN/DEPUTY_ADMIN を要求する（予約の更新・キャンセル）。
     * 越境 bookingId は 404、権限なしは 403。正当な本人操作（自分の予約を自分でキャンセル）を壊さない。
     */
    public void requireBookingOwnerOrAdmin(String scopeType, Long scopeId, Long bookingId, Long userId) {
        FacilityBookingEntity booking = findBookingOrThrow(bookingId);
        SharedFacilityEntity facility = matchBookingScope(booking, scopeType, scopeId);
        accessControlService.checkOwnerOrAdmin(
                userId, booking.getBookedBy(), facility.getScopeId(), facility.getScopeType());
    }

    // ════════════════════════════════════════════════════════════════════
    // 内部ヘルパー（entity 由来 scope 解決 + BOLA 存在秘匿）
    // ════════════════════════════════════════════════════════════════════

    private SharedFacilityEntity resolveFacilityInScope(String scopeType, Long scopeId, Long facilityId) {
        return facilityRepository.findByIdAndScopeTypeAndScopeId(facilityId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(FacilityErrorCode.FACILITY_NOT_FOUND));
    }

    private SharedFacilityEntity resolveBookingFacilityInScope(String scopeType, Long scopeId, Long bookingId) {
        FacilityBookingEntity booking = findBookingOrThrow(bookingId);
        return matchBookingScope(booking, scopeType, scopeId);
    }

    /**
     * 予約の属する施設を取得し、その施設の scope が URL パスの scope と一致することを検証する。
     * 一致しなければ {@code FACILITY_006}（404）で BOLA 存在秘匿する（パスの scope を鵜呑みにしない）。
     */
    private SharedFacilityEntity matchBookingScope(FacilityBookingEntity booking, String scopeType, Long scopeId) {
        SharedFacilityEntity facility = facilityRepository.findById(booking.getFacilityId())
                .orElseThrow(() -> new BusinessException(FacilityErrorCode.BOOKING_NOT_FOUND));
        if (!facility.getScopeType().equals(scopeType) || !facility.getScopeId().equals(scopeId)) {
            throw new BusinessException(FacilityErrorCode.BOOKING_NOT_FOUND);
        }
        return facility;
    }

    private FacilityBookingEntity findBookingOrThrow(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BusinessException(FacilityErrorCode.BOOKING_NOT_FOUND));
    }
}
