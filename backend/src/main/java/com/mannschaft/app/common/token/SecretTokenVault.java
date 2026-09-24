package com.mannschaft.app.common.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

/**
 * 秘密トークンの「金庫」。乱数生成 → ハッシュ保管 → 照合 の作法をここ 1 箇所に集約する。
 *
 * <p>招待・共有リンク・ワンタイム URL など、「知っている者だけが通れる合言葉」を扱う全ての機能が
 * この部品を利用することを想定する。招待・期限・使用回数といった業務語彙は一切持たない
 * （持つと他の用途で再利用できなくなるため）。</p>
 *
 * <h2>なぜ平文を保存しないのか</h2>
 * <p>トークンは「それを知っていること」だけが認証の根拠であり、パスワードと同じ性質を持つ。
 * 平文のまま DB に保存すると、DB のダンプ流出・バックアップの誤公開・SQL インジェクション等で
 * テーブルが 1 度読まれただけで、攻撃者は有効な招待リンクをそのまま復元して行使できる。
 * ハッシュだけを保存しておけば、DB が漏れてもそこから平文リンクを復元することはできない
 * （SHA-256 は一方向であり、32 バイト＝256 ビットの乱数は総当たりできない）。</p>
 *
 * <h2>なぜ定数時間比較なのか</h2>
 * <p>{@link String#equals} は最初に食い違ったバイトで即座に false を返すため、
 * 「何文字目まで一致したか」が比較に要した時間の差として外部から観測できてしまう。
 * 攻撃者はこの差を手がかりに 1 文字ずつ正解へ近づける（タイミング攻撃）ことができる。
 * {@link MessageDigest#isEqual} は全バイトを走査してから結果を返すため、
 * 一致の度合いが処理時間に漏れない。よって照合には必ず {@link #matches} を使うこと。</p>
 */
@Component
public class SecretTokenVault {

    /** 乱数バイト長。256 ビット。UUID（122 ビット）より高いエントロピーを確保する。 */
    private static final int TOKEN_BYTE_LENGTH = 32;

    private static final String HASH_ALGORITHM = "SHA-256";

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 新しい秘密トークンを発行する。
     *
     * <p>{@link SecureRandom} で 32 バイトを生成し、hex64 の平文と、その SHA-256 ハッシュ（hex64）を返す。
     * 平文はこの戻り値以外のどこにも保持されない（この部品は状態として平文を抱え込まない）。</p>
     *
     * @return 平文と保存用ハッシュの対
     */
    public IssuedToken issue() {
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);
        String plaintext = HexFormat.of().formatHex(bytes);
        return new IssuedToken(plaintext, hash(plaintext));
    }

    /**
     * 新しい秘密トークンを、平文表現 <b>Base64URL（パディング無し・43 文字）</b>で発行する。
     *
     * <p>乱数の強度・ハッシュ方式は {@link #issue()} と完全に同一で、平文の<b>文字表現だけ</b>が異なる。
     * hex64 より短いため、URL やメール本文に載せる招待リンクなど「人の目に触れる合言葉」に向く。</p>
     *
     * <p><b>なぜ 2 通りの表現を金庫が持つのか:</b> 表現形式は利用側の都合（リンクの長さ・既存トークンとの
     * 見た目の整合）で決まるものであり、乱数生成とハッシュ照合という金庫の本質は共通である。
     * 利用側が独自に Base64 変換を書き始めると、そこから乱数長やハッシュ方式の私有実装が再び生えるため、
     * 表現の選択肢は金庫側に置く。</p>
     *
     * @return 平文（Base64URL・43 文字）と保存用ハッシュ（hex64）の対
     */
    public IssuedToken issueBase64Url() {
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new IssuedToken(plaintext, hash(plaintext));
    }

    /**
     * 平文トークンから保存用ハッシュを計算する。
     *
     * <p>DB 検索は「この戻り値」を一意索引で等値検索することで行う。</p>
     *
     * @param plaintext 平文トークン（null 不可）
     * @return SHA-256 ハッシュの hex64 表現
     */
    public String hash(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext must not be null");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashed = digest.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 は全 JVM でサポート必須のため到達不能
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * 提示された平文トークンが、保存済みハッシュと一致するかを判定する。
     *
     * <p>比較は定数時間で行う（理由はクラス Javadoc「なぜ定数時間比較なのか」を参照）。
     * {@code equals} に置き換えてはならない。</p>
     *
     * @param plaintext  提示された平文トークン
     * @param storedHash DB から取り出した保存済みハッシュ
     * @return 一致すれば true。いずれかが null の場合は false
     */
    public boolean matches(String plaintext, String storedHash) {
        if (plaintext == null || storedHash == null) {
            return false;
        }
        return constantTimeEquals(hash(plaintext), storedHash);
    }

    /**
     * 2 つのハッシュ文字列を定数時間で比較する。
     *
     * @param a 比較対象
     * @param b 比較対象
     * @return 一致すれば true
     */
    public boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
