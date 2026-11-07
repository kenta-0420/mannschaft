package com.mannschaft.app.common.architecture.plaintexttoken;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * fixture（番人1 陽性）: 平文の合言葉を保存している Entity。
 *
 * <ul>
 *   <li>{@code token}: フィールド名が token 系語彙に一致する String（違反）</li>
 *   <li>{@code passcode}: フィールド名が token 系語彙に一致する char[]（違反）</li>
 *   <li>{@code displayCode}: フィールド名は一致しないが {@code @Column(name = "share_key")} が一致する（違反）</li>
 *   <li>{@code tokenId}・{@code tokenExpiresAt}・{@code leaseToken}: 名前は一致するが型が
 *       String/char[] でない（検査対象外）</li>
 * </ul>
 *
 * <p>番人3の陽性 fixture から呼ばれる setter と、Lombok の builder を模した {@link Builder} を持つ。</p>
 */
@Entity
@Table(name = "plaintext_guard_fixture_plain")
public class PlaintextTokenFixtureEntity {

    @Id
    private Long id;

    private String token;

    private char[] passcode;

    @Column(name = "share_key")
    private String displayCode;

    private Long tokenId;

    private LocalDateTime tokenExpiresAt;

    private UUID leaseToken;

    public Long getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public char[] getPasscode() {
        return passcode;
    }

    public void setPasscode(char[] passcode) {
        this.passcode = passcode;
    }

    public String getDisplayCode() {
        return displayCode;
    }

    public Long getTokenId() {
        return tokenId;
    }

    public LocalDateTime getTokenExpiresAt() {
        return tokenExpiresAt;
    }

    public void setTokenExpiresAt(LocalDateTime tokenExpiresAt) {
        this.tokenExpiresAt = tokenExpiresAt;
    }

    public UUID getLeaseToken() {
        return leaseToken;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Lombok {@code @Builder} が生成する入れ子 builder を模したもの（番人3の builder 経路の陽性用）。 */
    public static final class Builder {

        private final PlaintextTokenFixtureEntity target = new PlaintextTokenFixtureEntity();

        public Builder token(String token) {
            target.token = token;
            return this;
        }

        public PlaintextTokenFixtureEntity build() {
            return target;
        }
    }
}
