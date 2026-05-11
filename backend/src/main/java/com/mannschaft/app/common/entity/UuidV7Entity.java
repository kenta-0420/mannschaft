package com.mannschaft.app.common.entity;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

/**
 * UUIDv7（時刻順ソート可能）を主キーとする Entity 基底クラス。
 *
 * <p>Hibernate 6.2+ の {@code @UuidGenerator(style = TIME)} を利用し、
 * 時刻情報を埋め込んだ単調増加 UUID を生成する。
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
 * @see org.hibernate.annotations.UuidGenerator
 */
@MappedSuperclass
public abstract class UuidV7Entity {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    public UUID getId() {
        return id;
    }
}
