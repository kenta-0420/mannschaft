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

    /**
     * 都道府県内で名称が完全一致する市区町村を取得する（地域名→コード逆引き用）。
     *
     * <p>政令市区（例: {@code 札幌市中央区}）も独立行として存在するため、完全一致で拾える。
     * 同名が複数存在し得る理論上のケースに備えて List を返す（通常は 0〜1 件）。</p>
     */
    List<CityEntity> findByPrefectureCodeAndNameOrderByCodeAsc(String prefectureCode, String name);

    /**
     * 都道府県内で名称が前方一致する市区町村をコード順で取得する（逆引きの前方一致フォールバック用）。
     */
    List<CityEntity> findByPrefectureCodeAndNameStartingWithOrderByCodeAsc(String prefectureCode, String namePrefix);
}
