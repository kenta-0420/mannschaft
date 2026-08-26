package com.mannschaft.app.common.persistence.probe;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * issue #2545 の「ネイティブクエリのスカラ型は本当に {@code BigInteger} で返るのか」実測用の
 * ダミー Entity（テストソースセット専用）。
 *
 * <p>Spring Data JPA の {@code JpaRepository<T, ID>} は Entity 型を要求するが、
 * 本実測が使うのは {@link UnsignedIdProbeRepository} のネイティブクエリのみであり、
 * この Entity 自身は一度も JPA 経由で読み書きしない。
 * そのため本番テーブルにはマップせず、専用の名前を持たせて衝突を避けている
 * （{@code users} にマップすると全 {@code @SpringBootTest} の {@code ddl-auto=create} と競合する）。</p>
 *
 * <p>本番 Entity 走査（{@code FlywayFromScratchMigrationTest#scanMappedClasses}）は
 * テストソースセット由来のクラスを出所で機械的に除外するため、
 * 「Flyway に対応テーブルが無い」ことで番人テストを赤くすることはない
 * （{@code DummyD6ExposedEntity} と同じ扱い）。</p>
 */
@Entity
@Table(name = "unsigned_id_probe")
public class UnsignedIdProbeEntity {

    @Id
    @Column(name = "id")
    private Long id;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
}
