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
 * CMP-260901-1538 柱③-A 検分第3巡是正: 組織・チーム名称の同名確認フロー用の
 * <b>行ロック専用テーブル</b>のマッピング。
 *
 * <p>実データを保持しない（アプリコードから本エンティティを通じて読み書きすることは無く、
 * {@code DuplicateNameGuardServiceImpl} がネイティブ SQL の
 * {@code INSERT ... ON DUPLICATE KEY UPDATE} で直接ロックを取得する。{@code created_at} は
 * DB 側の {@code DEFAULT CURRENT_TIMESTAMP(6)} に任せるため、このエンティティ経由での
 * 書き込みは行わない＝{@code nullable=true} としアプリ側の欠落挿入を許容する）。</p>
 *
 * <p>このクラス自体は主にテスト環境（{@code ddl-auto=create} でスキーマを Entity から
 * 生成する test profile）でテーブルが確実に作成されるようにするために存在する
 * （本番/開発環境は Flyway マイグレーション {@code V200.*__create_duplicate_name_locks.sql}
 * がスキーマを作る）。</p>
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

    @Column(name = "created_at", nullable = true)
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

        @Column(name = "name_key", nullable = false, length = 64)
        private String nameKey;
    }
}
