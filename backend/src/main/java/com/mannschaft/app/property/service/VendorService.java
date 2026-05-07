package com.mannschaft.app.property.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.property.PropertyHistoryErrorCode;
import com.mannschaft.app.property.VendorCategory;
import com.mannschaft.app.property.entity.VendorEntity;
import com.mannschaft.app.property.repository.VendorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 業者マスタ管理サービス。
 *
 * <p>F09.13 設計書 §3 vendors テーブル / §4 業者マスタ API に対応。
 * 業者の CRUD（論理削除）・サジェスト検索・スコープ重複チェックを担う。</p>
 *
 * <p>スコープは {@code TEAM} / {@code ORGANIZATION} の 2 種のみを許可する
 * （設計書 §3 vendors の {@code scope_type} CHECK 制約と一致）。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class VendorService {

    /** 設計書 §3: vendors.scope_type の CHECK 制約に一致する許可セット。 */
    private static final Set<String> ALLOWED_SCOPE_TYPES = Set.of("TEAM", "ORGANIZATION");

    /** サジェスト検索の最大返却件数（設計書 §4: 業者サジェスト 10 件）。 */
    private static final int SUGGEST_MAX = 10;

    private final VendorRepository vendorRepository;

    // =========================================================================
    // DTO（Service 内 record）
    // =========================================================================

    /** 業者新規作成・更新リクエスト。 */
    public record VendorUpsertRequest(
            String name,
            String nameKana,
            VendorCategory category,
            String phone,
            String email,
            String website,
            String postalCode,
            String address,
            String representative,
            String contactPerson,
            String licenseNumber,
            LocalDate licenseExpiry,
            String note) {}

    // =========================================================================
    // 公開メソッド
    // =========================================================================

    /**
     * 業者を新規作成する。
     *
     * <p>同一スコープ内で {@code name} の重複が発生しないようチェックする
     * （vendors の {@code uq_vendors_scope_name} 制約と整合）。</p>
     *
     * @throws BusinessException PROPERTY_004（入力不正）/ PROPERTY_006（名称重複）
     */
    @Transactional
    public VendorEntity createVendor(String scopeType, Long scopeId, Long createdBy,
                                     VendorUpsertRequest req) {
        validateScope(scopeType, scopeId);
        validateName(req.name());

        // 重複チェック: 同一スコープ × 同一名称（未削除）の業者は登録不可
        Optional<VendorEntity> dup =
                vendorRepository.findByScopeTypeAndScopeIdAndNameAndDeletedAtIsNull(
                        scopeType, scopeId, req.name());
        if (dup.isPresent()) {
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_006);
        }

        VendorEntity entity = VendorEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(req.name())
                .nameKana(req.nameKana())
                .category(req.category())
                .phone(req.phone())
                .email(req.email())
                .website(req.website())
                .postalCode(req.postalCode())
                .address(req.address())
                .representative(req.representative())
                .contactPerson(req.contactPerson())
                .licenseNumber(req.licenseNumber())
                .licenseExpiry(req.licenseExpiry())
                .note(req.note())
                .isActive(true)
                .createdBy(createdBy)
                .build();

        VendorEntity saved = vendorRepository.save(entity);
        log.info("業者新規作成: id={}, scope={}/{}, name={}",
                saved.getId(), scopeType, scopeId, saved.getName());
        return saved;
    }

    /**
     * 業者を更新する。
     *
     * <p>{@code name} を変更した場合は重複チェックを再実行する。
     * 業者名スナップショット（{@code property_work_packages.vendor_name_snapshot}）は
     * 設計書 §8 の判断により更新しない（既存パッケージは作成時の業者名を固定保持）。</p>
     *
     * @throws BusinessException PROPERTY_005（業者なし）/ PROPERTY_006（名称重複）
     */
    @Transactional
    public VendorEntity updateVendor(String scopeType, Long scopeId, Long vendorId, VendorUpsertRequest req) {
        VendorEntity vendor = findVendorOrThrow(vendorId);
        // IDOR 防止: パスから受け取った scope と vendor の所属 scope が一致することを確認
        ensureScopeMatches(vendor, scopeType, scopeId);
        validateName(req.name());

        if (!vendor.getName().equals(req.name())) {
            Optional<VendorEntity> dup =
                    vendorRepository.findByScopeTypeAndScopeIdAndNameAndDeletedAtIsNull(
                            vendor.getScopeType(), vendor.getScopeId(), req.name());
            if (dup.isPresent() && !dup.get().getId().equals(vendorId)) {
                throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_006);
            }
        }

        vendor.updateBasicInfo(req.name(), req.nameKana(), req.category());
        vendor.updateContact(req.phone(), req.email(), req.website(),
                req.postalCode(), req.address());
        vendor.updatePersonnel(req.representative(), req.contactPerson());
        vendor.updateLicense(req.licenseNumber(), req.licenseExpiry());
        vendor.updateNote(req.note());

        VendorEntity saved = vendorRepository.save(vendor);
        log.info("業者更新: id={}, name={}", saved.getId(), saved.getName());
        return saved;
    }

    /**
     * 業者を 1 件取得する（未削除のみ）。
     *
     * <p>IDOR 防止のため、パスから受け取った scope と vendor の所属 scope の一致を必ず確認する。
     * 不一致の場合は PROPERTY_005（業者なし）扱いとして 404 を返し、他スコープの存在を漏らさない。</p>
     *
     * @throws BusinessException PROPERTY_005
     */
    public VendorEntity getVendor(String scopeType, Long scopeId, Long vendorId) {
        VendorEntity vendor = findVendorOrThrow(vendorId);
        ensureScopeMatches(vendor, scopeType, scopeId);
        return vendor;
    }

    /**
     * 業者の所属 scope と引数の scope が一致することを確認する（IDOR 防止）。
     */
    private void ensureScopeMatches(VendorEntity vendor, String scopeType, Long scopeId) {
        if (!vendor.getScopeType().equals(scopeType) || !vendor.getScopeId().equals(scopeId)) {
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_005);
        }
    }

    /**
     * スコープ配下の有効業者をページング取得する。
     */
    public Page<VendorEntity> listActiveVendors(String scopeType, Long scopeId, Pageable pageable) {
        validateScope(scopeType, scopeId);
        return vendorRepository.findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(
                scopeType, scopeId, pageable);
    }

    /**
     * カテゴリで絞り込んだ有効業者一覧を取得する。
     */
    public List<VendorEntity> listByCategory(String scopeType, Long scopeId, VendorCategory category) {
        validateScope(scopeType, scopeId);
        return vendorRepository.findByScopeTypeAndScopeIdAndCategoryAndIsActiveTrueAndDeletedAtIsNull(
                scopeType, scopeId, category);
    }

    /**
     * オートコンプリート用サジェスト検索。
     *
     * <p>{@code name} または {@code nameKana} に検索文字列を含む有効業者を、
     * {@link #SUGGEST_MAX} 件まで {@code nameKana} 昇順で返す。</p>
     */
    public List<VendorEntity> suggestByName(String scopeType, Long scopeId, String query) {
        validateScope(scopeType, scopeId);
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Pageable limit = PageRequest.of(0, SUGGEST_MAX);
        return vendorRepository.searchByKeyword(scopeType, scopeId, query.trim(), limit);
    }

    /**
     * 業者を論理削除する。
     *
     * <p>削除しても既存パッケージの {@code vendor_name_snapshot} で業者名表示は維持される。</p>
     */
    @Transactional
    public void softDelete(String scopeType, Long scopeId, Long vendorId) {
        VendorEntity vendor = findVendorOrThrow(vendorId);
        // IDOR 防止
        ensureScopeMatches(vendor, scopeType, scopeId);
        vendor.softDelete();
        vendorRepository.save(vendor);
        log.info("業者論理削除: id={}", vendorId);
    }

    // =========================================================================
    // 内部メソッド
    // =========================================================================

    private VendorEntity findVendorOrThrow(Long vendorId) {
        return vendorRepository.findByIdAndDeletedAtIsNull(vendorId)
                .orElseThrow(() -> new BusinessException(PropertyHistoryErrorCode.PROPERTY_005));
    }

    private void validateScope(String scopeType, Long scopeId) {
        if (scopeType == null || !ALLOWED_SCOPE_TYPES.contains(scopeType) || scopeId == null) {
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_004);
        }
    }

    private void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 150) {
            throw new BusinessException(PropertyHistoryErrorCode.PROPERTY_004);
        }
    }
}
