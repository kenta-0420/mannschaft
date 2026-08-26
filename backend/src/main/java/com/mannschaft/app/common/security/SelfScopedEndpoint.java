package com.mannschaft.app.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * このエンドポイントが<b>自己スコープで閉じている</b>——すなわち検索・更新の対象が
 * <b>認証主体（{@code SecurityUtils.getCurrentUserId()} 等）から解決されるスコープに束縛され、
 * リクエストで他人の識別子を指定する余地が構造的に無い</b>——ことを示す<b>監査済マーカー</b>。
 *
 * <p>番人（{@code AuthzControllerGuardArchTest}）は Controller の Mapping メソッドが
 * 「認可シグナルを一切持たない」事故を CI 静的解析で機械的に検知するが、その判定は
 * 白名簿クラス（{@code AccessControlService} / {@code ContentVisibilityChecker} /
 * {@code *AccessGuard} / {@code *AccessService}）への呼び出し辺（直接または浅い委譲）を
 * 見るものである。自己スコープ EP は<b>そもそも他人のデータに到達する経路が存在しない</b>ため
 * 認可判定を呼ぶ必要がなく、番人の呼び出しグラフ判定には原理的に引っかからない。
 * 本注釈はそうしたエンドポイントを「認可漏れではなく構造的に安全である」と
 * 監査を経て明示承認するためのマーカーである。</p>
 *
 * <h2>意味の限定 — 「どこかで認可済み」ではない</h2>
 * <p>本注釈が主張するのは <b>到達不能性</b>（他人のデータには構造的に到達できない）であり、
 * <b>「どこかで認可判定が実施されている」ことではない</b>。両者は別の主張であり、
 * 混同すると後年の監査で「認可の実在箇所」を見失う。したがって:</p>
 * <ul>
 *   <li>リクエストがリソース ID・スコープ ID を受け取り、それを検索条件に用いる EP には
 *       <b>付与してはならない</b>。その場合は実体由来スコープの照合＋権限判定
 *       （{@code AccessControlService} 等）が必要であり、本注釈は代替にならない。</li>
 *   <li>「操作者 == 所有者」を直接比較して拒否している EP は、到達可能性そのものは存在する
 *       （＝判定を外せば他人へ届く）ため本注釈の対象ではない。認可判定の実体があるので
 *       白名簿クラスへ寄せるか、判定箇所を Javadoc に明記して従来どおり扱う。</li>
 * </ul>
 *
 * <h2>{@link AuthorizedInService} との使い分け</h2>
 * <table border="1">
 *   <caption>4 マーカーの主張の違い</caption>
 *   <tr><th>注釈</th><th>主張</th></tr>
 *   <tr><td>{@code SelfScopedEndpoint}</td>
 *       <td><b>他人のデータには到達できない</b>（スコープが認証主体に束縛される）</td></tr>
 *   <tr><td>{@link AuthorizedInService}</td>
 *       <td>白名簿クラスを介さず <b>Service 内の別方式で認可済み</b>
 *           （webhook 署名検証・capability トークン等）</td></tr>
 *   <tr><td>{@link AuthorizedByPathConfig}</td>
 *       <td>{@code SecurityConfig} のパス単位 {@code hasRole()} 等で宣言的に強制済み</td></tr>
 *   <tr><td>{@link IntentionallyPublic}</td>
 *       <td>{@code permitAll()} 配下で意図的に無認可公開</td></tr>
 * </table>
 * <p>過去の波では自己スコープ EP に {@link AuthorizedInService} を流用した例があるが、
 * 本注釈の新設をもって<b>その転用は今後禁止</b>とする（証跡の意味が異なるため）。</p>
 *
 * <h2>付与の必須条件 — 契約テストが要る（番人が機械的に強制する）</h2>
 * <p>{@link #value()} に自己スコープである根拠を必ず書くこと（空文字・空白のみは不可）。
 * さらに、本注釈を付与した各エンドポイントには
 * <b>対応する契約テストの存在が必須</b>であり、これは番人テスト
 * {@code SelfScopedEndpointMarkerGuardTest} が機械的に検証する。
 * 契約テスト側に「どのエンドポイントの自己スコープ性を固定しているか」を
 * {@code <Controller 単純名>#<メソッド名>} の形で明記することでリンクが成立する
 * （詳細な判定規則は同番人テストの Javadoc を参照）。</p>
 *
 * <p><b>濫用の禁止</b>: 自己スコープでない EP へ本注釈を付与することは、
 * 監査の証跡を偽る行為であり禁止する。本注釈が保証するのは「監査を経て到達不能性を確認し、
 * その事実を契約テストで固定した」ことであって、番人の出力を静かにすることではない。
 * 対象の DTO・リポジトリクエリ・検索条件が変更された際は、束縛が崩れていないか
 * 必ず本注釈の妥当性を再評価すること。</p>
 *
 * <h2>メソッドにのみ付与できる（クラス単位は不可）</h2>
 * <p>他の 3 マーカーは {@code ElementType.TYPE} も許すが、本注釈は<b>メソッド専用</b>である。
 * 到達不能性は「そのエンドポイントが何を検索条件に使うか」に依存する<b>エンドポイント単位の性質</b>で
 * あり、実際の Controller は自己スコープ EP（{@code getMyXxx} 等）と
 * リソース ID を受け取る EP を同一クラス内に併せ持つのが通例である。
 * クラス単位の付与を許すと、1 箇所の付与でクラス内の全 EP が承認扱いとなり、
 * かつ契約テストの必須化もクラス単位に薄まってしまうため、意図的に許可しない。</p>
 *
 * @see AuthorizedInService
 * @see AuthorizedByPathConfig
 * @see IntentionallyPublic
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SelfScopedEndpoint {

    /**
     * 自己スコープである根拠（必須・空文字および空白のみは不可）。
     *
     * <p>「検索・更新の対象がどのように認証主体へ束縛されているか」を、
     * 追跡可能な形（クラス名・メソッド名・行番号）で書くこと。</p>
     *
     * <p>例: {@code "リポジトリクエリのスコープIDが認証主体の userId に束縛される"
     * + "（TodoService#findMyTodos が SecurityUtils.getCurrentUserId() のみを検索条件に使う）"}</p>
     */
    String value();
}
