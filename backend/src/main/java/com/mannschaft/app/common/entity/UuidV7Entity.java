package com.mannschaft.app.common.entity;

import com.mannschaft.app.common.UuidV7;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;

import java.util.Objects;
import java.util.UUID;

/**
 * UUIDv7（時刻順ソート可能）を主キーとする Entity 基底クラス。
 *
 * <p>RFC 9562 準拠の UUIDv7 を永続化直前に生成し、
 * 時刻情報を埋め込んだ UUID を生成する。
 * これにより B-Tree インデックスの page split を抑制し、
 * INSERT 性能を UUIDv4 と比較して大幅に改善できる。</p>
 *
 * <h2>適用対象</h2>
 * <ul>
 *   <li>新規テーブル作成時（既存テーブルの ID 型変更は破壊的変更につき禁止）</li>
 *   <li>DDL の id カラム型は {@code CHAR(36)} または {@code BINARY(16)} を推奨</li>
 * </ul>
 *
 * <h2>使用例</h2>
 * <pre>{@code
 * @Entity
 * @Table(name = "my_new_table")
 * public class MyNewEntity extends UuidV7Entity {
 *     // ... フィールド定義
 * }
 * }</pre>
 *
 * <h2>equals / hashCode について</h2>
 * <p>本クラスは ID ベースで {@link #equals(Object)} と {@link #hashCode()} を実装する。
 * サブクラスは {@code @EqualsAndHashCode(callSuper = true)} を指定することで
 * ID ベースの同値性判定を継承できる。</p>
 *
 * @see UuidV7
 */
@SuperBuilder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@MappedSuperclass
public abstract class UuidV7Entity {

    @Id
    private UUID id;

    @PrePersist
    protected void assignId() {
        if (id == null) {
            id = UuidV7.generate();
        }
    }

    public UUID getId() {
        return id;
    }

    /**
     * ID を明示的に設定する。
     * 通常は永続化直前に自動採番するため呼び出し不要だが、
     * テストでモック用エンティティを作る際や、UUID を外部から引き継ぐ
     * 特殊なユースケース（イベント駆動の再構築など）で利用する。
     */
    public void setId(UUID id) {
        this.id = id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UuidV7Entity that)) {
            return false;
        }
        // ID が両方とも未採番（永続化前）の場合は同一インスタンスでない限り別個体として扱う
        if (this.id == null || that.id == null) {
            return false;
        }
        return Objects.equals(this.id, that.id);
    }

    @Override
    public int hashCode() {
        // 永続化前後で hash が変化しないよう、クラスベースの hash を返す
        // （Hibernate でも推奨されるプロキシ対応パターン）
        return getClass().hashCode();
    }
}
