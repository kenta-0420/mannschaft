package com.mannschaft.app.notification.fanout;

import com.mannschaft.app.common.i18n.DeliveryLocales;

/**
 * fan-out の受信者 1 件（Issue #2871）。
 *
 * <p>従来 {@code List<Long>}（user_id のみ）だった受信者ページの要素を、
 * <b>user_id ＋ 配信ロケール</b>の組へ広げたもの。受信者ソースの keyset クエリが
 * {@code users} を PK で JOIN して {@code locale} も一緒に取るため、ロケール解決のための
 * DB 往復は増えない（{@code UserLocaleCache} を 50 万人配信が洗い流す問題も同時に避ける）。</p>
 *
 * <h2>locale の意味（設計上の明示）</h2>
 * <p>ここでの locale は「<b>その受信者ページを取得した時点の</b>利用者の locale」である。
 * 配信の途中で利用者が言語を切り替えた場合、切り替え前に取得済みのページは切り替え前の
 * locale で配信される。fan-out の不変条件は「欠落なし・at-least-once」であって
 * 「言語切り替えの即時反映」ではないため、これを許容する。</p>
 *
 * <h2>正規化はここで必ず通る（AC-4 の構造的担保）</h2>
 * <p>コンパクトコンストラクタで {@link DeliveryLocales#normalize} を必ず適用する。
 * 受信者ソースが 4 実装あっても、どの実装が生の DB 値をそのまま渡してきても、
 * このレコードを経由する限り locale は配信 bucket 6 種のいずれかに正規化される。
 * 「正規化を呼び忘れた実装」が構造的に作れない。</p>
 *
 * @param userId 受信者の user_id
 * @param locale 配信ロケール（{@link DeliveryLocales#TAGS} のいずれかへ正規化済み）
 */
public record FanoutRecipient(long userId, String locale) {

    public FanoutRecipient {
        locale = DeliveryLocales.normalize(locale);
    }
}
