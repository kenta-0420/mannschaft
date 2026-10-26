package com.mannschaft.app.matching.repository;

import com.mannschaft.app.matching.entity.PrefectureEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 都道府県マスタリポジトリ。
 */
public interface PrefectureRepository extends JpaRepository<PrefectureEntity, String> {

    /**
     * 全都道府県をコード順で取得する。
     */
    List<PrefectureEntity> findAllByOrderByCodeAsc();
}
