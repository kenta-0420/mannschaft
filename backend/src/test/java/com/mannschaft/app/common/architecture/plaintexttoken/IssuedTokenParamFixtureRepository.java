package com.mannschaft.app.common.architecture.plaintexttoken;

import com.mannschaft.app.common.token.IssuedToken;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * fixture（番人4 陽性）: {@link IssuedToken} を Repository メソッドの引数に取っている（平文ごと永続化層へ渡す形）。
 * 違反として検出されるのは {@code countByIssued} の引数。{@code findAllByIdIn} は陰性（引数が Long の集合）。
 */
@NoRepositoryBean
public interface IssuedTokenParamFixtureRepository extends JpaRepository<IssuedTokenHoldingFixtureEntity, Long> {

    long countByIssued(IssuedToken issued);

    List<IssuedTokenHoldingFixtureEntity> findAllByIdIn(List<Long> ids);
}
