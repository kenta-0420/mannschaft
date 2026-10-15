package com.mannschaft.app.corkboard.repository;

import com.mannschaft.app.corkboard.entity.CorkboardGroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * コルクボードセクションリポジトリ。
 */
public interface CorkboardGroupRepository extends JpaRepository<CorkboardGroupEntity, Long> {

    /**
     * ボード内のセクション一覧を表示順で取得する。
     */
    List<CorkboardGroupEntity> findByCorkboardIdOrderByDisplayOrderAsc(Long corkboardId);

    /**
     * ボードIDとセクションIDで取得する。
     */
    Optional<CorkboardGroupEntity> findByIdAndCorkboardId(Long id, Long corkboardId);

    /**
     * ボード内のセクション数を取得する。
     */
    long countByCorkboardId(Long corkboardId);
}
