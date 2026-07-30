package com.mannschaft.app.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.ZoneId;
import java.util.Set;

/**
 * {@link ValidTimezone} の検証実装（Issue #2487 項目 4）。
 *
 * <p>{@code null} は「未指定＝更新しない」として通す。非 null の値は
 * <b>実在する IANA タイムゾーン名</b>であることを要求する。</p>
 *
 * <p>判定は {@link ZoneId#getAvailableZoneIds()}（JVM の tzdb が持つ IANA 名の集合）への
 * 所属で行う。{@code ZoneId.of()} が通るかどうかだけで判定すると {@code "+09:00"} / {@code "Z"} /
 * {@code "UTC+9"} といった<b>固定オフセット表記も通ってしまう</b>。固定オフセットは夏時間を追随できず、
 * 該当地域のユーザーの日付境界が年に 2 回ずれるため、保存値としては認めない。</p>
 */
public class TimezoneValidator implements ConstraintValidator<ValidTimezone, String> {

    /** JVM の tzdb が持つ IANA タイムゾーン名の集合（不変・起動時に一度だけ解決する）。 */
    private static final Set<String> AVAILABLE_ZONE_IDS = Set.copyOf(ZoneId.getAvailableZoneIds());

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            // 未指定＝更新しない。必須にしたい場合は @NotNull と併用する。
            return true;
        }
        return AVAILABLE_ZONE_IDS.contains(value.trim());
    }
}
