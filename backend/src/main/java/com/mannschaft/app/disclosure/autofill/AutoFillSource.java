package com.mannschaft.app.disclosure.autofill;

/**
 * 重要事項説明書（F09.14）の自動引用ソース 1 件を表すストラテジ。
 *
 * <p>各実装は Spring Bean として登録し、
 * {@link com.mannschaft.app.disclosure.service.DisclosureAutoFillService} が
 * 起動時に {@link #key()} をキーとする不変 Map（SOURCE_REGISTRY）を構築する。
 * これにより設計書 §6.4 のホワイトリスト方式（任意の Java プロパティ参照禁止）を実現する。</p>
 *
 * <p>新しい引用元を追加する際の手順:</p>
 * <ol>
 *   <li>{@code com.mannschaft.app.disclosure.autofill.sources} 配下に
 *       {@link AutoFillSource} を実装した Spring {@code @Component} クラスを追加</li>
 *   <li>{@link #key()} で設計書 §5.2 の引用元キーを返す</li>
 *   <li>{@link DisclosureFormTemplateValidator} 側でキーが許容範囲内であることが
 *       自動的に保証される（registry を共有するため）</li>
 * </ol>
 */
public interface AutoFillSource {

    /**
     * ホワイトリストキー（例: {@code "organization.name"}）。
     *
     * <p>設計書 §5.2 の引用元キー一覧と一致させる必要がある。</p>
     */
    String key();

    /**
     * 引用元データを解決する。
     *
     * <p>該当データが存在しない場合は {@code null} を返してよい。例外を投げてはならない
     * （上位の {@link com.mannschaft.app.disclosure.service.DisclosureAutoFillService}
     * が WARN ログ出力して空欄として扱う）。</p>
     *
     * @param context スコープ・対象居室・許諾フラグ・filter を保持するコンテキスト
     * @return 引用値。スカラ値（String/Number/Boolean）または {@code List<Map<String,Object>>}（AUTO_TABLE 用）
     */
    Object resolve(AutoFillContext context);
}
