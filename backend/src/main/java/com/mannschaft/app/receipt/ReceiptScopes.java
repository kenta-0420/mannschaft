package com.mannschaft.app.receipt;

/**
 * 領収書スコープに関する定数（F08.12 §3.0）。
 *
 * <p>PLATFORM スコープには実体となるテナント行が存在しないため、{@code scope_id} には
 * センチネル値 {@code 0} を用いる。実在する team / organization の id は 1 以上のため
 * 衝突しない。</p>
 *
 * <p><b>この定数を経由しない {@code 0L} リテラルを receipt ドメインに散らしてはならない。</b>
 * 「なぜ 0 なのか」の根拠が失われ、team_id = 0 との取り違えを招くためである。</p>
 */
public final class ReceiptScopes {

    /** PLATFORM スコープの scope_id。 */
    public static final Long PLATFORM_SCOPE_ID = 0L;

    private ReceiptScopes() {
    }
}
