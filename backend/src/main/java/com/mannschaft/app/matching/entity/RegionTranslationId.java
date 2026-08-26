package com.mannschaft.app.matching.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * {@link RegionTranslationEntity} の複合主キー（{@code code} × {@code lang}）。
 *
 * <p>CLAUDE.md 原則6 のマスタ例外（全テナント共通の静的参照データ）に該当するため、
 * UUIDv7 ではなく自然キーを採用する。</p>
 */
public class RegionTranslationId implements Serializable {

    private String code;
    private String lang;

    protected RegionTranslationId() {
    }

    public RegionTranslationId(String code, String lang) {
        this.code = code;
        this.lang = lang;
    }

    public String getCode() {
        return code;
    }

    public String getLang() {
        return lang;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RegionTranslationId that)) {
            return false;
        }
        return Objects.equals(code, that.code) && Objects.equals(lang, that.lang);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, lang);
    }
}
