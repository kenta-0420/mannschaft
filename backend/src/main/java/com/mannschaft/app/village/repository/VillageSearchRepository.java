package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/**
 * 村検索専用リポジトリ（F17.1 Phase 1 §4.2）。
 *
 * <p>本リポジトリは {@link VillageRepository} を変更せずに検索能力を追加するために
 * 独立した interface として用意した（B1 の Repository 変更禁止ルール準拠）。
 * 動的フィルタ（{@code q} / {@code category} / {@code type}）が必要なため
 * {@link JpaSpecificationExecutor} を継承し、Service 層で
 * {@link com.mannschaft.app.village.service.VillageSearchSpecifications} を組み立てて使用する。</p>
 *
 * <p>本リポジトリ単体での書き込み操作は想定しないが、JPA の制約上
 * {@link JpaRepository} を継承する必要があるため、書き込み系メソッドは Service 層で呼ばないこと。</p>
 */
public interface VillageSearchRepository
        extends JpaRepository<VillageEntity, UUID>, JpaSpecificationExecutor<VillageEntity> {
}
