package com.mannschaft.app.common.architecture.plaintexttoken;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * fixture（番人2 陽性＝免除の誤用）: 対応フィールドの債務注釈が他の戦役の cmp を名乗っている（AC-27）。
 * 無効な免除は免除として扱わない。
 */
@NoRepositoryBean
public interface MisdeclaredExemptionFixtureRepository
        extends JpaRepository<MisdeclaredExemptionFixtureEntity, Long> {

    Optional<MisdeclaredExemptionFixtureEntity> findByPreviewToken(String previewToken);
}
