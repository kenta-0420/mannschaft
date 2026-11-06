package com.mannschaft.app.common.architecture.plaintexttoken;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * fixture（番人2 陰性＝免除）: 対応フィールドに正しい免除注釈がある列の検索。
 *
 * <ul>
 *   <li>{@code findBySigningSecret}: 対応フィールドが {@code @PlaintextSecretRequired}（免除）</li>
 *   <li>{@code findByInviteToken}: 対応フィールドが {@code @PlaintextTokenDebt(cmp = "CMP-260826-1457")}（免除）</li>
 * </ul>
 */
@NoRepositoryBean
public interface ExemptedSecretFixtureRepository extends JpaRepository<ExemptedSecretFixtureEntity, Long> {

    Optional<ExemptedSecretFixtureEntity> findBySigningSecret(String signingSecret);

    Optional<ExemptedSecretFixtureEntity> findByInviteToken(String inviteToken);
}
