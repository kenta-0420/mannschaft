package com.mannschaft.app.matching.repository;

import com.mannschaft.app.matching.entity.CityEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 市区町村マスタリポジトリ。
 */
public interface CityRepository extends JpaRepository<CityEntity, String> {

    /**
     * 都道府県内の市区町村一覧をコード順で取得する。
     */
    List<CityEntity> findByPrefectureCodeOrderByCodeAsc(String prefectureCode);
}
