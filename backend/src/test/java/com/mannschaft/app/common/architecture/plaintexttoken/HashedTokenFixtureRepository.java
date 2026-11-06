package com.mannschaft.app.common.architecture.plaintexttoken;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.query.Param;

/**
 * fixture（番人2 陰性）: ハッシュ列で引く Repository（違反にならない）。
 */
@NoRepositoryBean
public interface HashedTokenFixtureRepository extends JpaRepository<HashedTokenFixtureEntity, Long> {

    Optional<HashedTokenFixtureEntity> findByTokenHash(String tokenHash);

    @Query("SELECT e FROM HashedTokenFixtureEntity e WHERE e.tokenHash = :hash")
    Optional<HashedTokenFixtureEntity> lookupByHash(@Param("hash") String hash);
}
