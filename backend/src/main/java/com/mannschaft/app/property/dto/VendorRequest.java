package com.mannschaft.app.property.dto;

import com.mannschaft.app.property.VendorCategory;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 業者作成・更新リクエスト DTO（F09.13 Phase 1-δ）。
 *
 * <p>設計書 {@code docs/features/F09.13_property_history.md} §3 vendors テーブル
 * カラム長と整合する。{@code version} は更新時の楽観的ロック用（PUT 時必須、POST 時は無視可）。</p>
 */
public record VendorRequest(
        @NotBlank
        @Size(max = 150)
        String name,

        @Size(max = 200)
        String nameKana,

        VendorCategory category,

        @Size(max = 30)
        String phone,

        @Email
        @Size(max = 255)
        String email,

        @Size(max = 500)
        String website,

        @Size(max = 10)
        String postalCode,

        @Size(max = 255)
        String address,

        @Size(max = 100)
        String representative,

        @Size(max = 100)
        String contactPerson,

        @Size(max = 100)
        String licenseNumber,

        LocalDate licenseExpiry,

        String note,

        Boolean isActive,

        /** 楽観的ロック用 version（PUT 時必須）。 */
        Long version) {
}
