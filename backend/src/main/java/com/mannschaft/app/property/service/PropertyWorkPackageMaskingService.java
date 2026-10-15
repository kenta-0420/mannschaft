package com.mannschaft.app.property.service;

import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.property.WorkPackageVisibility;
import com.mannschaft.app.property.entity.PropertyWorkPackageEntity;
import com.mannschaft.app.property.entity.VendorEntity;
import com.mannschaft.app.role.service.PermissionGroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 物件履歴パッケージのマスキング処理サービス。
 *
 * <p>F09.13 設計書 §5.5 「マスキング処理（金額・連絡先）」に対応。
 * パッケージの {@link WorkPackageVisibility} と閲覧者ロール（{@link UserScopeRoleSnapshot}）
 * から、金額・業者連絡先のマスク有無を判定する。</p>
 *
 * <p>判定マトリクス（設計書 §5.5）:</p>
 * <table>
 *   <caption>visibility × ロール × マスク</caption>
 *   <tr><th>visibility</th><th>ADMIN</th><th>DEPUTY_ADMIN(MANAGE)†</th><th>DEPUTY_ADMIN(VIEW)†</th><th>MEMBER</th><th>SUPPORTER</th></tr>
 *   <tr><td>ADMINS_ONLY</td><td>全表示</td><td>全表示</td><td>金額マスク</td><td>不可視</td><td>不可視</td></tr>
 *   <tr><td>MEMBERS_ONLY</td><td>全表示</td><td>全表示</td><td>金額マスク</td><td>全表示</td><td>不可視</td></tr>
 *   <tr><td>MEMBERS_MASKED</td><td>全表示</td><td>全表示</td><td>金額マスク</td><td>金額マスク</td><td>不可視</td></tr>
 *   <tr><td>PUBLIC_MASKED</td><td>全表示</td><td>全表示</td><td>金額マスク</td><td>金額マスク</td><td>金額マスク</td></tr>
 * </table>
 * <p>† DEPUTY_ADMIN の MANAGE / VIEW 区別は、{@link PermissionGroupService#hasPermission}
 * で {@code PROPERTY_HISTORY_MANAGE} 権限保有有無を照会して判定する（Phase 2-α-3）。</p>
 *
 * <p><strong>本サービスの役割範囲</strong>:</p>
 * <ul>
 *   <li>マスク済みビュー（{@link MaskedView}）の生成 — 金額カラムは {@code null}、文字列は "●●●" に置換</li>
 *   <li>不可視判定（{@link #isVisible}）— 可視/不可視のロール×可視性判定（DTO 層へのフラグ提供）</li>
 *   <li>マスク後 vendor 情報の生成 — 連絡先（phone/email/address/contactPerson）のマスク</li>
 * </ul>
 *
 * <p>F00 の {@link com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver}
 * は「閲覧可能か」までしか扱わない（IDOR 防止と DRAFT/SCHEDULED ガード）。本サービスは
 * 閲覧可能と判定された後の「金額マスクの有無」を決定する補助層である。</p>
 *
 * <p><strong>DEPUTY_ADMIN(VIEW) の判定について（Phase 2-α-3 厳密化済）</strong>:
 * {@code UserScopeRoleSnapshot} は「DEPUTY_ADMIN(MANAGE) 系」と「DEPUTY_ADMIN(VIEW) 系」を
 * 区別しないため、設計書 §5.5 で求められる「VIEW のみ → 金額マスク」を完全再現するには、
 * 別途「権限グループ {@code PROPERTY_HISTORY_MANAGE} を保有するか」の照会が必要となる。
 * 本サービスは {@link PermissionGroupService#hasPermission(Long, String, Long, String)} を
 * 経由して当該パーミッション保有有無を判定し、保有時は全表示・非保有時は金額マスクへ分岐する。
 * permission 定義は Flyway V61.015 で {@code PROPERTY_HISTORY_MANAGE} /
 * {@code PROPERTY_HISTORY_VIEW} を投入済（DEPUTY_ADMIN は天井のみ、ADMIN は is_default=1）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PropertyWorkPackageMaskingService {

    /** F09.13 物件履歴台帳 — 全表示権限のパーミッション名（V61.015 で投入）。 */
    private static final String PERMISSION_PROPERTY_HISTORY_MANAGE = "PROPERTY_HISTORY_MANAGE";

    private final PermissionGroupService permissionGroupService;

    /** マスク文字列（金額以外の文字列フィールド用）。設計書 §5.5「●●●円」の表示形式に準拠。 */
    private static final String MASK_STRING = "●●●";

    // =========================================================================
    // ロール定数
    // =========================================================================

    /** 役職ロール: ADMIN（{@code RolePriority} で priority マップ登録の値）。 */
    private static final String ROLE_ADMIN = "ADMIN";

    /** 役職ロール: DEPUTY_ADMIN。 */
    private static final String ROLE_DEPUTY_ADMIN = "DEPUTY_ADMIN";

    /** 役職ロール: MEMBER。 */
    private static final String ROLE_MEMBER = "MEMBER";

    /** 役職ロール: SUPPORTER。 */
    private static final String ROLE_SUPPORTER = "SUPPORTER";

    // =========================================================================
    // 公開メソッド
    // =========================================================================

    /**
     * パッケージにマスキングを適用したビューを生成する。
     *
     * <p>不可視と判定された場合は {@link MaskedView#hidden} な値を返す（呼び出し側は
     * これを取り出して 404 / 403 を判断するか、リスト除外する）。</p>
     *
     * @param entity         対象パッケージ
     * @param vendor         紐付く業者（null 可。null の場合は vendor フィールドがすべて null）
     * @param viewerUserId   閲覧者のユーザーID（DEPUTY_ADMIN MANAGE 権限照会で利用、null 可: 匿名相当）
     * @param viewerSnapshot 閲覧者のスコープロールスナップショット
     * @return マスク後のビュー
     */
    public MaskedView applyMasking(PropertyWorkPackageEntity entity, VendorEntity vendor,
                                   Long viewerUserId, UserScopeRoleSnapshot viewerSnapshot) {
        if (entity == null) {
            return MaskedView.hidden();
        }
        boolean visible = isVisible(entity, viewerSnapshot);
        if (!visible) {
            return MaskedView.hidden();
        }
        boolean canViewAmount = canViewAmount(entity.getVisibility(), entity.getScopeType(),
                entity.getScopeId(), viewerUserId, viewerSnapshot);

        Long estimated = canViewAmount ? entity.getEstimatedAmount() : null;
        Long contract = canViewAmount ? entity.getContractAmount() : null;
        Long actual = canViewAmount ? entity.getActualAmount() : null;

        MaskedVendorView vendorView = vendor == null
                ? MaskedVendorView.empty()
                : maskVendor(vendor, canViewAmount);

        return new MaskedView(true, canViewAmount, estimated, contract, actual, vendorView);
    }

    /**
     * 不可視判定。設計書 §5.5 の「不可視」セルを返す。
     *
     * <p>{@code visibility} とユーザーロールから、当該パッケージが見えるか否かを判定する。
     * F00 Resolver の {@code canView} と粒度は同じだが、本サービスは Resolver の前段で
     * Service 層が利用するヘルパとして提供される（DTO 層に boolean フラグで信号を返す）。</p>
     */
    public boolean isVisible(PropertyWorkPackageEntity entity, UserScopeRoleSnapshot snapshot) {
        if (entity == null || snapshot == null) {
            return false;
        }
        if (snapshot.isSystemAdmin()) {
            return true;
        }
        ScopeKey scope = scopeOf(entity);
        String role = scope != null ? snapshot.roleByScope().get(scope) : null;
        WorkPackageVisibility v = entity.getVisibility();
        if (v == null) {
            // fail-closed: visibility 不明は不可視
            return false;
        }
        return switch (v) {
            // ADMIN/DEPUTY_ADMIN のみ
            case ADMINS_ONLY -> ROLE_ADMIN.equals(role) || ROLE_DEPUTY_ADMIN.equals(role);
            // MEMBER 以上（SUPPORTER 不可）
            case MEMBERS_ONLY, MEMBERS_MASKED -> ROLE_ADMIN.equals(role)
                    || ROLE_DEPUTY_ADMIN.equals(role)
                    || ROLE_MEMBER.equals(role);
            // SUPPORTER 以上
            case PUBLIC_MASKED -> ROLE_ADMIN.equals(role)
                    || ROLE_DEPUTY_ADMIN.equals(role)
                    || ROLE_MEMBER.equals(role)
                    || ROLE_SUPPORTER.equals(role);
        };
    }

    // =========================================================================
    // 内部メソッド — 金額閲覧可否判定 / vendor マスク
    // =========================================================================

    /**
     * 金額閲覧可否を判定する。設計書 §5.5 マトリクス完全準拠。
     *
     * <p>SystemAdmin / ADMIN は常に閲覧可。DEPUTY_ADMIN は権限グループ経由で
     * {@code PROPERTY_HISTORY_MANAGE} を保有する場合のみ全表示、保有しない（VIEW 相当）場合は
     * 金額マスク（Phase 2-α-3 で {@link PermissionGroupService#hasPermission} により厳密判定）。
     * MEMBER は visibility=MEMBERS_ONLY 時のみ全表示、それ以外はマスク。
     * SUPPORTER は MEMBER に準じるが MEMBERS_ONLY/MEMBERS_MASKED は不可視（{@link #isVisible} で除外）。</p>
     */
    private boolean canViewAmount(WorkPackageVisibility visibility, String scopeType, Long scopeId,
                                  Long userId, UserScopeRoleSnapshot snapshot) {
        if (snapshot.isSystemAdmin()) {
            return true;
        }
        ScopeKey scope = (scopeType != null && scopeId != null) ? new ScopeKey(scopeType, scopeId) : null;
        String role = scope != null ? snapshot.roleByScope().get(scope) : null;
        if (role == null || visibility == null) {
            return false;
        }
        if (ROLE_ADMIN.equals(role)) {
            return true;
        }
        if (ROLE_DEPUTY_ADMIN.equals(role)) {
            // Phase 2-α-3: 権限グループ経由で PROPERTY_HISTORY_MANAGE を保有する場合のみ全表示。
            // 保有しない（VIEW のみ）場合は金額マスクとし、設計書 §5.5 マトリクスを忠実に再現する。
            return permissionGroupService.hasPermission(userId, scopeType, scopeId,
                    PERMISSION_PROPERTY_HISTORY_MANAGE);
        }
        if (ROLE_MEMBER.equals(role)) {
            // MEMBER は MEMBERS_ONLY のみ全表示、それ以外（MEMBERS_MASKED/PUBLIC_MASKED）はマスク。
            // ADMINS_ONLY は本来不可視だが {@link #isVisible} 段階で弾かれるためここに来ない。
            return visibility == WorkPackageVisibility.MEMBERS_ONLY;
        }
        if (ROLE_SUPPORTER.equals(role)) {
            // SUPPORTER は PUBLIC_MASKED のみ可視で常にマスク（金額閲覧不可）。
            return false;
        }
        return false;
    }

    /**
     * 業者連絡先をマスクする。金額閲覧不可の場合、phone/email/address/contactPerson を
     * 設計書 §5.5 マスク対象に従って "●●●" に置換する。
     */
    private MaskedVendorView maskVendor(VendorEntity vendor, boolean canViewAmount) {
        if (canViewAmount) {
            return new MaskedVendorView(
                    vendor.getId(),
                    vendor.getName(),
                    vendor.getCategory() != null ? vendor.getCategory().name() : null,
                    vendor.getPhone(),
                    vendor.getEmail(),
                    vendor.getAddress(),
                    vendor.getContactPerson());
        }
        // マスク版: name と category だけは表示（業者名は重説書での表示が必要なため）
        return new MaskedVendorView(
                vendor.getId(),
                vendor.getName(),
                vendor.getCategory() != null ? vendor.getCategory().name() : null,
                vendor.getPhone() != null ? MASK_STRING : null,
                vendor.getEmail() != null ? MASK_STRING : null,
                vendor.getAddress() != null ? MASK_STRING : null,
                vendor.getContactPerson() != null ? MASK_STRING : null);
    }

    private static ScopeKey scopeOf(PropertyWorkPackageEntity entity) {
        if (entity.getScopeType() == null || entity.getScopeId() == null) {
            return null;
        }
        return new ScopeKey(entity.getScopeType(), entity.getScopeId());
    }

    // =========================================================================
    // 値オブジェクト
    // =========================================================================

    /**
     * マスキング適用後のパッケージビュー。
     *
     * @param visible       閲覧可能かどうか（false の場合、それ以外のフィールドは無効）
     * @param canViewAmount 金額閲覧可能かどうか（DTO 層が UI フラグとして利用）
     * @param estimatedAmount マスク適用後の見積金額（マスク時は null）
     * @param contractAmount  マスク適用後の契約金額（マスク時は null）
     * @param actualAmount    マスク適用後の実施金額（マスク時は null）
     * @param vendor          マスク適用後の業者ビュー（業者なし or 不可視時は空ビュー）
     */
    public record MaskedView(
            boolean visible,
            boolean canViewAmount,
            Long estimatedAmount,
            Long contractAmount,
            Long actualAmount,
            MaskedVendorView vendor) {

        public static MaskedView hidden() {
            return new MaskedView(false, false, null, null, null, MaskedVendorView.empty());
        }
    }

    /**
     * マスキング適用後の業者ビュー。
     *
     * @param id            vendor.id
     * @param name          業者名（常に表示。重説書での参照を可能にするため）
     * @param category      業者カテゴリ（常に表示）
     * @param phone         電話（マスク時は "●●●"）
     * @param email         メール（マスク時は "●●●"）
     * @param address       住所（マスク時は "●●●"）
     * @param contactPerson 担当者氏名（マスク時は "●●●"）
     */
    public record MaskedVendorView(
            Long id,
            String name,
            String category,
            String phone,
            String email,
            String address,
            String contactPerson) {

        public static MaskedVendorView empty() {
            return new MaskedVendorView(null, null, null, null, null, null, null);
        }
    }
}
