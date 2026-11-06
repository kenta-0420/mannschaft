package com.mannschaft.app.common.token;

import java.util.regex.Pattern;

/**
 * 「知っている者だけが通れる合言葉」を Valkey に置くときのキーを作る唯一の入口（CMP-260826-1457 §2-4 番人6）。
 *
 * <p>キーの接頭辞は {@code static final String} のコンパイル時定数であることが多く、javac が使用箇所へ
 * 値をインライン化するため、バイトコードからは定数の参照を辿れない。そこで、合言葉のキーは必ずこの
 * static メソッドを通して作る約束にし、番人は {@code INVOKESTATIC BearerSecretValkeyKey.of} の呼び出しを
 * 手がかりに「同じメソッド内で自前の乱数（{@code UUID.randomUUID()}・{@code SecureRandom#nextBytes}）を
 * 使っていないか」を検査する。</p>
 *
 * <p>第2引数は必ず金庫のハッシュ（SHA-256 hex64）でなければならない。平文の UUID や Base64URL の平文を渡す誤用は、
 * 番人より先にここで実行時例外として止める。例外メッセージには渡された値を含めない（値が平文だった場合にログへ残さない）。</p>
 */
public final class BearerSecretValkeyKey {

    /** SHA-256 の hex 表記の長さ。 */
    private static final int HASH_HEX_LENGTH = 64;

    private static final Pattern HASH_HEX_PATTERN = Pattern.compile("[0-9a-fA-F]{" + HASH_HEX_LENGTH + "}");

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
        if (namespace == null || namespace.isEmpty()) {
            throw new IllegalArgumentException("Valkey キーの接頭辞が空です");
        }
        if (tokenHash == null || !HASH_HEX_PATTERN.matcher(tokenHash).matches()) {
            throw new IllegalArgumentException("tokenHash は SHA-256 の hex64 でなければなりません（平文を渡していないか確認）");
        }
        return namespace + tokenHash;
    }
}
