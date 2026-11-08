package com.mannschaft.app.common.architecture.plaintexttoken;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * fixture（番人2 陽性）: MappedSuperclass から継承した平文の秘密列 {@code secret} を検索する Repository。
 * 対応フィールドの解決が Entity 本体の宣言だけでなく継承元まで辿ることを確かめる。
 */
@NoRepositoryBean
public interface PlaintextSecretSubFixtureRepository extends JpaRepository<PlaintextSecretSubFixtureEntity, Long> {

    Optional<PlaintextSecretSubFixtureEntity> findBySecret(String secret);
}
