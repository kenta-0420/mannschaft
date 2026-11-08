package com.mannschaft.app.common.architecture.plaintexttoken;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.query.Param;

/**
 * fixture（番人2 陽性）: 平文トークン列を等値検索する Repository。
 *
 * <ul>
 *   <li>{@code findByToken}・{@code existsByToken}: derived query の名前で平文列を引く（違反）</li>
 *   <li>{@code lookupByValue}: 名前は token 系でないが、{@code @Query} 本文が {@code e.token =} で平文列を引く（違反）</li>
 * </ul>
 */
@NoRepositoryBean
public interface PlaintextTokenFixtureRepository extends JpaRepository<PlaintextTokenFixtureEntity, Long> {

    Optional<PlaintextTokenFixtureEntity> findByToken(String token);

    boolean existsByToken(String token);

    @Query("SELECT e FROM PlaintextTokenFixtureEntity e WHERE e.token = :value")
    Optional<PlaintextTokenFixtureEntity> lookupByValue(@Param("value") String value);
}
