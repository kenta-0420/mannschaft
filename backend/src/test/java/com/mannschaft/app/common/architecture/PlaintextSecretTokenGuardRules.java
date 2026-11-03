package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.List;

/**
 * 番人 {@link PlaintextSecretTokenGuardArchTest}（CMP-260826-1457 §2-4 番人1〜6）の判定ロジックの単一正準。
 *
 * <p>本番の番人（{@link PlaintextSecretTokenGuardArchTest}）と自己検証（{@link PlaintextSecretTokenGuardConditionTest}）は、
 * どちらもこのクラスの同じメソッドを呼ぶ（判定ロジックを二重に書かない）。判定はリフレクション・ArchUnit・バイトコードで行い、
 * ソースの字句解析は使わない。</p>
 *
 * <h2>試練（W0 red）の段階のスタブ</h2>
 * <p>本クラスは<b>試練が置いたスタブ</b>であり、全メソッドが空の結果を返す。判定の実装は W0 の出陣で行う。
 * そのため、陽性 fixture を「違反として検出すること」を求める自己検証テストと、本番走査の非空性（検査対象を1件以上拾うこと）
 * を求める番人テストは、実装されるまで失敗する（red）。</p>
 *
 * <h2>結果の表記の約束（テストはこの約束に依存する）</h2>
 * <ul>
 *   <li>フィールドは {@code 完全修飾クラス名 + "." + フィールド名}（例 {@code com.example.FooEntity.token}）</li>
 *   <li>メソッドは {@code 完全修飾クラス名 + "#" + メソッド名}（例 {@code com.example.FooService#issue}）。
 *       番人4で Repository メソッドの引数を指すときも、そのメソッドの表記を使う</li>
 *   <li>各要素は上の識別子そのものか、識別子の後に半角空白を1つ置いて理由を続けたもの
 *       （例 {@code "com.example.FooEntity.token 平文の token 系列（_hash でなく免除注釈も無い）"}）</li>
 * </ul>
 *
 * <h2>各番人の判定（陣立て書 §2-4 の要約）</h2>
 * <ol>
 *   <li><b>番人1（列）</b>: {@code @Entity}・{@code @MappedSuperclass} の宣言フィールドで、型が {@code String}/{@code char[]} かつ
 *       フィールド名または {@code @Column(name)} が {@code (?i)(token|secret|passcode|invite_code|share_key)} に一致するものを検査対象とする。
 *       列名（{@code @Column(name)}、無ければフィールド名の snake_case）が {@code _hash} で終わるもの、
 *       有効な免除注釈が付くものは違反でない。</li>
 *   <li><b>番人2（Repository）</b>: Spring Data の {@code Repository} を継承する全インタフェース（{@code @NoRepositoryBean} を含む）の
 *       宣言メソッドを検査する。名前に Token/Secret 系の語を含み Hash を含まない検索メソッド、または {@code @Query} 本文が
 *       平文の token 系プロパティを等値比較しているメソッドは、対応する Entity フィールド（継承元を含む）が有効な免除注釈を
 *       持たない限り違反。</li>
 *   <li><b>番人3（自前発行）</b>: 同じメソッドの中で {@code UUID.randomUUID()} または {@code SecureRandom#nextBytes} を呼び、かつ
 *       {@code @Entity} クラス（またはその入れ子の builder）の token 系の名前のメソッドで String/char[] を受け取るものを呼ぶメソッドは違反。
 *       メソッドに有効な免除注釈があれば免除。</li>
 *   <li><b>番人4（IssuedToken の永続化）</b>: {@code IssuedToken} を {@code @Entity}/{@code @MappedSuperclass} のフィールド型
 *       （{@code @Transient} を含む）や Repository メソッドの引数型に使うことは違反（免除なし）。</li>
 *   <li><b>番人6（列を通らない書き込み）</b>: (a) {@code INSERT INTO}/{@code UPDATE} を含む文字列定数を持ち、かつ自前乱数を呼ぶメソッド、
 *       (b) {@code BearerSecretValkeyKey.of} を呼び、かつ自前乱数を呼ぶメソッドは違反。メソッドに有効な免除注釈があれば免除。
 *       {@code BearerSecretValkeyKey.of} を呼ばない Valkey の一時キー（CSV プレビュー等）は対象外。</li>
 * </ol>
 *
 * <p><b>有効な免除注釈</b>: {@code @PlaintextSecretRequired}（{@code reason} が空白以外の文字を含む）または
 * {@code @PlaintextTokenDebt}（{@code cmp} が {@link #DEBT_CMP} と一致）。無効な免除注釈が付いた要素は免除されず違反になる。</p>
 */
final class PlaintextSecretTokenGuardRules {

    /** {@code @PlaintextTokenDebt} の {@code cmp} として唯一許される値。 */
    static final String DEBT_CMP = "CMP-260826-1457";

    private PlaintextSecretTokenGuardRules() {
    }

    /**
     * 1つの番人の判定結果。
     *
     * @param inspected  検査対象として拾った要素（番人1: 候補フィールド、番人2: 候補メソッド、番人3・6: 自前乱数を呼ぶメソッド、
     *                   番人4: 検査した Entity・Repository の型）
     * @param exempted   違反の形だが、有効な免除注釈で免除された要素
     * @param violations 違反
     */
    record GuardResult(List<String> inspected, List<String> exempted, List<String> violations) {

        static GuardResult empty() {
            return new GuardResult(List.of(), List.of(), List.of());
        }
    }

    /** 番人1: 平文の token 系列。 */
    static GuardResult plaintextColumns(JavaClasses classes) {
        return GuardResult.empty();
    }

    /** 番人2: 平文の token 系列を検索する Repository メソッド。 */
    static GuardResult plaintextRepositoryLookups(JavaClasses classes) {
        return GuardResult.empty();
    }

    /** 番人3: 金庫を通さない自前発行（自前乱数と Entity の token 系 setter/builder の同居）。 */
    static GuardResult selfIssuedTokens(JavaClasses classes) {
        return GuardResult.empty();
    }

    /** 番人4: {@code IssuedToken} の永続化（Entity のフィールド型・Repository 引数）。 */
    static GuardResult issuedTokenPersistence(JavaClasses classes) {
        return GuardResult.empty();
    }

    /** 番人6: JDBC 直接 INSERT/UPDATE と自前乱数の同居、{@code BearerSecretValkeyKey.of} と自前乱数の同居。 */
    static GuardResult rawWritesAndBearerKeysWithSelfRandom(JavaClasses classes) {
        return GuardResult.empty();
    }
}
