package com.mannschaft.app.common.token;

/**
 * 「知っている者だけが通れる合言葉」を Valkey に置くときのキーを作る唯一の入口（CMP-260826-1457 §2-4 番人6）。
 *
 * <p>キーの接頭辞は {@code static final String} のコンパイル時定数であることが多く、javac が使用箇所へ
 * 値をインライン化するため、バイトコードからは定数の参照を辿れない。そこで、合言葉のキーは必ずこの
 * static メソッドを通して作る約束にし、番人は {@code INVOKESTATIC BearerSecretValkeyKey.of} の呼び出しを
 * 手がかりに「同じメソッド内で自前の乱数（{@code UUID.randomUUID()}・{@code SecureRandom#nextBytes}）を
 * 使っていないか」を検査する。</p>
 *
 * <p><b>W0 試練の段階のスタブ</b>: 呼び出しをコンパイルさせるためだけに置いている。実装（hex64 検査と
 * キーの組み立て）は出陣で行う。</p>
 */
public final class BearerSecretValkeyKey {

    private BearerSecretValkeyKey() {
    }

    /**
     * 合言葉を Valkey に置くキーを作る。
     *
     * @param namespace キーの接頭辞（例 {@code "mannschaft:wallet:share_token:"}）
     * @param tokenHash 金庫 {@link SecretTokenVault#hash(String)} の戻り値（SHA-256 hex64）
     * @return Valkey のキー
     * @throws IllegalArgumentException {@code tokenHash} が SHA-256 hex64 の形でないとき
     */
    public static String of(String namespace, String tokenHash) {
        throw new UnsupportedOperationException("未実装（CMP-260826-1457 W0 出陣で実装する）");
    }
}
