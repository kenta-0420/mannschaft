package com.mannschaft.app.matching.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 地域名の多言語訳エンティティ（F22.1 Phase2 E）。
 *
 * <p>{@code prefectures} / {@code cities} マスタは日本語名のみを持つ。本エンティティは
 * 「地域コード × 言語」で訳名を別管理する（マスタ非破壊）。{@code ja} は元マスタ名が正のため
 * 格納せず、訳が無い（コード,言語）の組はアプリ側で日本語名へフォールバックする。</p>
 *
 * <p><strong>主キー方針:</strong> 全テナント共通の静的なマスタ性データであり、行は固定的で
 * シャーディング時は全シャードへ複製される。CLAUDE.md 原則6（新規テーブル UUIDv7）の意図に
 * 該当しないマスタ例外として、自然キー（{@code code} × {@code lang}）の複合主キーを採用する。</p>
 *
 * <p><strong>FK 方針:</strong> {@code code} に都道府県(2桁)・市区町村(5桁)の双方を格納するため
 * 単一カラムへ FK は張れない。整合性は Service 層で保証する。</p>
 */
@Entity
@Table(name = "region_translations")
@IdClass(RegionTranslationId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RegionTranslationEntity {

    /** 地域コード（都道府県2桁 / 市区町村5桁の双方を格納）。 */
    @Id
    @Column(nullable = false, length = 5)
    private String code;

    /** 言語コード（en/zh/ko/es/de）。 */
    @Id
    @Column(nullable = false, length = 5)
    private String lang;

    /** 当該言語での地域表示名。 */
    @Column(nullable = false, length = 40)
    private String name;
}
