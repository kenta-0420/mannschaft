package com.mannschaft.app.common.architecture.plaintexttoken;

import com.mannschaft.app.common.token.IssuedToken;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * fixture（番人4 陽性）: {@link IssuedToken}（平文とハッシュの対）を Entity のフィールド型に使っている。
 *
 * <p>{@code @Transient} でも違反とする（平文を抱えた Entity が toString・監査ログ・キャッシュへ流れうるため）。
 * {@code @Transient} を付けているのは、Hibernate のスキーマ生成（{@code ddl-auto: create}）を壊さないためだけである。</p>
 */
@Entity
@Table(name = "plaintext_guard_fixture_issued_holder")
public class IssuedTokenHoldingFixtureEntity {

    @Id
    private Long id;

    @Transient
    private IssuedToken issued;

    public Long getId() {
        return id;
    }

    public IssuedToken getIssued() {
        return issued;
    }
}
