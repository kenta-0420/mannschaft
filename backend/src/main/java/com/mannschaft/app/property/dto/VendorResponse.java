package com.mannschaft.app.property.dto;

import com.mannschaft.app.property.VendorCategory;
import com.mannschaft.app.property.entity.VendorEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 業者レスポンス DTO（F09.13 Phase 1-δ）。
 *
 * <p>MEMBER 閲覧時は {@code phone}/{@code email}/{@code address}/{@code contactPerson}
 * を {@code null} としたい場合がある（設計書 §5.5）。本 DTO 自体はマスキング判定を持たず、
 * 呼び出し側（Controller / ExportService）が {@link com.mannschaft.app.property.service.PropertyWorkPackageMaskingService}
 * の結果を反映してフィールドを {@code null} 化する。</p>
 */
public record VendorResponse(
        Long id,
        String scopeType,
        Long scopeId,
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
        String note,
        Boolean isActive,
        Long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /**
     * 全フィールドを表示するレスポンスを生成する（ADMIN 等用）。
     */
    public static VendorResponse from(VendorEntity entity) {
        return new VendorResponse(
                entity.getId(),
                entity.getScopeType(),
                entity.getScopeId(),
                entity.getName(),
                entity.getNameKana(),
                entity.getCategory(),
                entity.getPhone(),
                entity.getEmail(),
                entity.getWebsite(),
                entity.getPostalCode(),
                entity.getAddress(),
                entity.getRepresentative(),
                entity.getContactPerson(),
                entity.getLicenseNumber(),
                entity.getLicenseExpiry(),
                entity.getNote(),
                entity.getIsActive(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    /**
     * 連絡先（phone/email/address/contactPerson）をマスクしたレスポンスを返す。
     * 設計書 §5.5「マスク対象」に準拠（金額閲覧不可ロール向け）。
     */
    public static VendorResponse masked(VendorEntity entity) {
        return new VendorResponse(
                entity.getId(),
                entity.getScopeType(),
                entity.getScopeId(),
                entity.getName(),
                entity.getNameKana(),
                entity.getCategory(),
                null,
                null,
                entity.getWebsite(),
                entity.getPostalCode(),
                null,
                entity.getRepresentative(),
                null,
                entity.getLicenseNumber(),
                entity.getLicenseExpiry(),
                entity.getNote(),
                entity.getIsActive(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
