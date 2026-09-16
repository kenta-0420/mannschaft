package com.mannschaft.app.common.duplicatename;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * CMP-260901-1538 柱③-A 検分第3〜4巡是正: 組織・チーム名称の同名確認フロー用の
 * <b>行ロック専用テーブル</b>のマッピング。
 *
 * <p>実データを保持しない（アプリコードから本エンティティを通じて読み書きすることは無く、
 * {@code DuplicateNameGuardServiceImpl} がネイティブ SQL の
 * {@code INSERT ... ON DUPLICATE KEY UPDATE} で直接ロックを取得する）。{@code created_at} は
 * DB 側の {@code DEFAULT CURRENT_TIMESTAMP(6)} で自動算出されるため、このエンティティ経由での
 * 書き込みは行わない。検分第4巡是正: {@code columnDefinition} を DDL
 * （{@code V200.*__create_duplicate_name_locks.sql}）の
 * {@code TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)} と完全一致させることで、
 * {@code nullable} 属性と実 DDL の不整合を無くす（test profile の {@code ddl-auto=create} が
 * 生成するスキーマでも同じ NOT NULL + DEFAULT になるため、ネイティブ INSERT が
 * {@code created_at} を指定しなくても DB 側のデフォルトで補われる）。</p>
 *
 * <p>{@code name_key} は検分第4巡是正で SHA-256 ハッシュから trim 済みの生の名称へ変更した
 * （{@code DuplicateNameGuardServiceImpl} 参照）。列長は organizations/teams の name 列と
 * 同じ VARCHAR(100)。</p>
 *
 * <p>このクラス自体は主にテスト環境（{@code ddl-auto=create} でスキーマを Entity から
 * 生成する test profile）でテーブルが確実に作成されるようにするために存在する
 * （本番/開発環境は Flyway マイグレーションがスキーマを作る）。</p>
 *
 * <p><b>UuidV7Entity 適用除外</b>（{@code docs/architecture/domain_db_design_principles.md}
 * 原則6の例外区分「マスタ例外」に準じる）: 本テーブルはテナント・ユーザーごとに行が増える
 * 通常のドメインテーブルではなく、正規化名の種類数だけ存在するロック専用の恒久データであり、
 * シャーディング時は全シャードへ同じ行をコピーする運用が自然で、原則6の意図
 * （将来シャーディング時の各ノード独立発番）に該当しない。よって自然キー（複合主キー）の
 * ままとする。</p>
 */
@Entity
@Table(name = "duplicate_name_locks")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class DuplicateNameLockEntity {

    @EmbeddedId
    private Id id;

    @Column(name = "created_at", nullable = false,
            columnDefinition = "TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")
    private Instant createdAt;

    /** 複合主キー（{@code scope_kind}, {@code name_key}）。 */
    @Embeddable
    @EqualsAndHashCode
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Id implements Serializable {

        @Column(name = "scope_kind", nullable = false, length = 32)
        @Enumerated(EnumType.STRING)
        private DuplicateNameScopeKind scopeKind;

        @Column(name = "name_key", nullable = false, length = 100)
        private String nameKey;
    }
}
