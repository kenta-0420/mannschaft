package com.mannschaft.app.common.entity;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.util.Objects;
import java.util.UUID;

/**
 * UUIDv7 を主キーとする Entity 基底クラスのうち、DDL を {@code CHAR(36)} で
 * 定義しているテーブル向けの基底。
 *
 * <p>{@link UuidV7Entity} は Hibernate のデフォルト JDBC 型（{@code BINARY}）に従って
 * 16 バイトの raw bytes を主キーへ書き込む。これは DDL が {@code BINARY(16)} の場合のみ
 * 正しく動作し、{@code CHAR(36)} カラムに対しては
 * {@code Incorrect string value: '\x9E/...'} という MySQL utf8mb4 のエラーとなる。</p>
 *
 * <p>F18 ポイントカードウォレットの DDL は {@code CHAR(36)} を採用しており、
 * 設計時点で Hibernate との互換性が考慮されていなかった。本クラスは
 * {@link JdbcTypeCode @JdbcTypeCode(SqlTypes.CHAR)} を付与することで
 * Hibernate に 36 文字の UUID 文字列を書き込ませ、DDL と一致させる。</p>
 *
 * <p>新規テーブルは原則として {@link UuidV7Entity}（{@code BINARY(16)} DDL）を採用すること。
 * 本クラスは既に {@code CHAR(36)} で main にマージされているテーブル群を救うための
 * 互換層であり、新規での採用は推奨しない。</p>
 */
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@MappedSuperclass
public abstract class UuidV7CharEntity {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID id;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UuidV7CharEntity that)) {
            return false;
        }
        if (this.id == null || that.id == null) {
            return false;
        }
        return Objects.equals(this.id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
