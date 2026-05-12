package com.mannschaft.app.weather.entity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * {@link PostalCodeEntity} の複合主キークラス。
 *
 * <p>自然複合キー {@code (countryCode, postalCode)} を表現する。
 * マスタテーブルのため UUIDv7 ではなく自然キーを採用（CLAUDE.md 原則 6 のマスタ例外条項）。</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class PostalCodeId implements Serializable {

    private static final long serialVersionUID = 1L;

    private String countryCode;
    private String postalCode;
}
