package com.mannschaft.app.todo.repository;

import com.mannschaft.app.todo.TodoStatusLabelScope;
import com.mannschaft.app.todo.entity.TodoStatusLabelEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * TODO カスタムステータスラベルリポジトリ（F02.3.1）。
 *
 * <p>論理削除（deleted_at IS NULL）を前提とした参照系メソッドを提供する。</p>
 */
public interface TodoStatusLabelRepository extends JpaRepository<TodoStatusLabelEntity, Long> {

    /**
     * 指定スコープ専用のアクティブラベル一覧を sort_order 昇順で取得する。
     * SYSTEM 既定ラベルは含まれないため、必要なら {@link #findAllSystemDefaults()} を併用する。
     */
    @Query("""
            SELECT l FROM TodoStatusLabelEntity l
            WHERE l.deletedAt IS NULL
              AND l.scopeType = :scopeType
              AND l.scopeId = :scopeId
            ORDER BY l.sortOrder ASC, l.id ASC
            """)
    List<TodoStatusLabelEntity> findActiveByScope(
            @Param("scopeType") TodoStatusLabelScope scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * SYSTEM 既定ラベル一覧を sort_order 昇順で取得する。
     */
    @Query("""
            SELECT l FROM TodoStatusLabelEntity l
            WHERE l.deletedAt IS NULL
              AND l.scopeType = com.mannschaft.app.todo.TodoStatusLabelScope.SYSTEM
            ORDER BY l.sortOrder ASC, l.id ASC
            """)
    List<TodoStatusLabelEntity> findAllSystemDefaults();

    /**
     * ID でアクティブラベル（deleted_at IS NULL）を取得する。
     */
    @Query("""
            SELECT l FROM TodoStatusLabelEntity l
            WHERE l.deletedAt IS NULL
              AND l.id = :id
            """)
    Optional<TodoStatusLabelEntity> findActiveById(@Param("id") Long id);

    /**
     * 指定スコープ内に同名のアクティブラベルが存在するかを判定する。
     * 重複検知に使用する。
     */
    @Query("""
            SELECT (COUNT(l) > 0) FROM TodoStatusLabelEntity l
            WHERE l.deletedAt IS NULL
              AND l.scopeType = :scopeType
              AND ((:scopeId IS NULL AND l.scopeId IS NULL) OR l.scopeId = :scopeId)
              AND l.name = :name
            """)
    boolean existsActiveByScopeAndName(
            @Param("scopeType") TodoStatusLabelScope scopeType,
            @Param("scopeId") Long scopeId,
            @Param("name") String name);

    /**
     * 指定スコープ内に同名のアクティブラベルが存在するかを判定する（指定 ID を除外）。
     * リネーム時の重複チェックに使用する。
     */
    @Query("""
            SELECT (COUNT(l) > 0) FROM TodoStatusLabelEntity l
            WHERE l.deletedAt IS NULL
              AND l.scopeType = :scopeType
              AND ((:scopeId IS NULL AND l.scopeId IS NULL) OR l.scopeId = :scopeId)
              AND l.name = :name
              AND l.id <> :excludeId
            """)
    boolean existsActiveByScopeAndNameExcludingId(
            @Param("scopeType") TodoStatusLabelScope scopeType,
            @Param("scopeId") Long scopeId,
            @Param("name") String name,
            @Param("excludeId") Long excludeId);

    /**
     * 指定スコープ内のアクティブラベル数を取得する（上限チェック用）。
     */
    @Query("""
            SELECT COUNT(l) FROM TodoStatusLabelEntity l
            WHERE l.deletedAt IS NULL
              AND l.scopeType = :scopeType
              AND ((:scopeId IS NULL AND l.scopeId IS NULL) OR l.scopeId = :scopeId)
            """)
    long countActiveByScope(
            @Param("scopeType") TodoStatusLabelScope scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * 指定ラベルを使用中の TODO 件数を取得する（削除前の使用中チェック用）。
     */
    @Query("""
            SELECT COUNT(t) FROM TodoEntity t
            WHERE t.deletedAt IS NULL
              AND t.statusLabelId = :labelId
            """)
    long countTodosUsing(@Param("labelId") Long labelId);
}
