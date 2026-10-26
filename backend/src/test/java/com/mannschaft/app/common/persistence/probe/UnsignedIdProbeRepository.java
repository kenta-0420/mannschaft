package com.mannschaft.app.common.persistence.probe;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * issue #2545 の実測用リポジトリ。
 *
 * <p>本番コードに実在する 2 つの受け取り形（{@code List<Long>} と射影インタフェース）を
 * {@code BIGINT UNSIGNED} 列（{@code users.id}）に対して再現し、
 * <b>実行時に何のクラスが返るか</b>を固定する。</p>
 *
 * <p>クエリ先は Flyway が構築した本番同一スキーマの {@code users} であり、
 * {@link UnsignedIdProbeEntity} のテーブルではない（ネイティブクエリなので Entity マッピングは無関係）。</p>
 */
public interface UnsignedIdProbeRepository extends JpaRepository<UnsignedIdProbeEntity, Long> {

    /**
     * 本番に 18 件存在する「{@code nativeQuery = true} で {@code List<Long>} を受ける」形。
     *
     * <p>Spring Data の {@code QueryExecutionResultHandler} はコレクション<b>型</b>は変換するが
     * <b>要素型</b>は変換しない、というのが #2514 の前提だった。要素の実行時型を実測する。</p>
     */
    @Query(value = "SELECT id FROM users ORDER BY id LIMIT 1", nativeQuery = true)
    List<Long> findIdsAsLongList();

    /**
     * 本番に 3 件存在する「射影インタフェースで {@code Long} を宣言する」形。
     *
     * <p>{@code ProjectingMethodInterceptor} → {@code DefaultConversionService} の
     * {@code NumberToNumber} で救われる、というのが issue #2545 の対抗仮説。</p>
     */
    @Query(value = "SELECT id AS id FROM users ORDER BY id LIMIT 1", nativeQuery = true)
    List<IdProjection> findIdsAsProjection();

    /**
     * 本番に 17 件存在する「{@code nativeQuery = true} で {@code List<Object[]>} を受ける」形。
     *
     * <p>{@code AdSegmentService} の {@code (Long) topVenues.get(0)[0]} という
     * <b>コードベース唯一の native 由来の直キャスト</b>と同じ形状（GROUP BY + COUNT の 2 列）を再現する。</p>
     */
    @Query(value = "SELECT id, COUNT(*) AS cnt FROM users GROUP BY id ORDER BY cnt DESC LIMIT 1",
            nativeQuery = true)
    List<Object[]> findIdAndCountAsObjectArray();

    /** 射影インタフェース（{@code Long} 宣言）。 */
    interface IdProjection {
        Long getId();
    }
}
