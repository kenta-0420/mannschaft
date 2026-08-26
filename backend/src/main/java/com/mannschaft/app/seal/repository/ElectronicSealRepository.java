package com.mannschaft.app.seal.repository;

import com.mannschaft.app.seal.SealVariant;
import com.mannschaft.app.seal.entity.ElectronicSealEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 電子印鑑リポジトリ。
 */
public interface ElectronicSealRepository extends JpaRepository<ElectronicSealEntity, Long> {

    /**
     * ユーザーの印鑑一覧を取得する。
     */
    List<ElectronicSealEntity> findByUserIdOrderByCreatedAtAsc(Long userId);

    /**
     * ユーザーIDとバリアントで印鑑を取得する。
     */
    Optional<ElectronicSealEntity> findByUserIdAndVariant(Long userId, SealVariant variant);

    /**
     * ユーザーIDとバリアントの組み合わせが存在するか確認する。
     */
    boolean existsByUserIdAndVariant(Long userId, SealVariant variant);

    /**
     * IDとユーザーIDで印鑑を取得する。
     */
    Optional<ElectronicSealEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * ユーザーの印鑑件数を取得する。
     */
    long countByUserId(Long userId);

    /**
     * 全印鑑を取得する（管理者用一括再生成）。
     */
    List<ElectronicSealEntity> findAllByOrderByUserIdAsc();

    /**
     * ユーザーの論理削除済み印鑑を物理削除する。
     *
     * <p>再生成時に論理削除済みレコードがユニーク制約（user_id, variant）に
     * 引っかかる問題を防ぐために使用する。
     * {@code @SQLRestriction} は SELECT に作用するが DELETE には作用しないため、
     * native query で削除対象を明示する。</p>
     *
     * @param userId ユーザーID
     * @return 削除件数
     */
    @Modifying
    @Query(value = "DELETE FROM electronic_seals WHERE user_id = :userId AND deleted_at IS NOT NULL", nativeQuery = true)
    int deleteByUserIdAndDeletedAtIsNotNull(@Param("userId") Long userId);

    /**
     * 論理削除済みの (user_id, variant) 行があれば「復活（undelete）+ 内容更新」する。
     *
     * <p><b>背景</b>: {@code electronic_seals} は UNIQUE KEY {@code uk_electronic_seals_user_variant}
     * (user_id, variant) を持つ。ユーザーが印鑑を削除（{@code deleted_at} セットの論理削除）後、
     * 同一 variant で再作成すると、{@link #existsByUserIdAndVariant} は {@code @SQLRestriction}
     * により論理削除済み行を無視して {@code false} を返すため作成処理が進むが、
     * 物理的には旧行が残っているため INSERT が UNIQUE 制約違反（{@code DataIntegrityViolationException}
     * → {@code GlobalExceptionHandler} の catch-all で 500/COMMON_999）になる。</p>
     *
     * <p><b>物理削除ではなく「復活」を選ぶ理由</b>: {@code seal_stamp_logs.seal_id} は
     * {@code electronic_seals(id)} を {@code ON DELETE RESTRICT} で参照しており（押印監査証跡のため
     * 削除時にクリーンアップされない）、過去に押印実績がある印鑑を物理削除しようとすると
     * この RESTRICT に阻まれて別種の 500 を招く。同じ主キー行を UPDATE で復活させれば
     * 物理 DELETE 自体が発生せず、この経路を安全に回避できる。</p>
     *
     * <p>{@code flushAutomatically}: 同一トランザクション内で直前に行われた永続化操作
     * （例: 論理削除の {@code save}）を native UPDATE の実行前に確実に DB へ反映する。
     * {@code clearAutomatically}: native UPDATE は Hibernate の一次キャッシュを迂回するため、
     * クリアしないと直後の再取得が旧状態の managed entity を返す
     * （復活後の displayText / seal_hash 等が旧値のままレスポンスされる）。</p>
     *
     * @param userId      ユーザーID
     * @param variant     バリアント（{@link com.mannschaft.app.seal.SealVariant#name()}）
     * @param displayText 新しい表示テキスト
     * @param svgData     新しいSVGデータ
     * @param sealHash    新しいハッシュ値
     * @return 更新件数（0 の場合は論理削除済み行が存在しない＝通常の INSERT でよい）
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE electronic_seals
            SET display_text = :displayText,
                svg_data = :svgData,
                seal_hash = :sealHash,
                generation_version = generation_version + 1,
                deleted_at = NULL
            WHERE user_id = :userId AND variant = :variant AND deleted_at IS NOT NULL
            """, nativeQuery = true)
    int reviveDeleted(@Param("userId") Long userId,
                       @Param("variant") String variant,
                       @Param("displayText") String displayText,
                       @Param("svgData") String svgData,
                       @Param("sealHash") String sealHash);
}
