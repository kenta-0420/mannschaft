# 認可 PROPAGATE 棚卸し台帳

認可の裏目付検出器（`AuthzGateEffectivenessAuditTest`、形②）が「保守的に合格」させている
PROPAGATE 箇所（2段抜けの疑いがある箇所）を独立に列挙し、戦役の規模を測るための台帳。

## PROPAGATE とは何か・なぜ静的に追えないのか

`AuthzGateEffectivenessAuditTest` の形②は、認可クラス（`*AccessGuard` / `*AccessService` /
`*AuthorizationService` / `AccessControlService`）が持つ DECISION 系メソッド（`can`/`is`/`has`/
`get`/`resolve` 等、`require`/`filter` 系を除く）の戻り値がローカル変数へ代入された場合、
その変数の同一ブロック内の全使用箇所を次のように分類する（同クラス javadoc L102-116）。

- `if (v)` / `while (v)` / `&&` / `||` / 三項条件、`.filter`/`anyMatch` 等、`throw` の一部 → **GATE**
- `return v` / `return f(v)` → **PROPAGATE**（判定は呼び元へ委ねられる）
- 小文字始まりメソッドの引数 → **PROPAGATE**（下流が enforce する可能性）
- `new XxxDto(..)` / `XxxResponse.from(..)` / `.builder()` 系のみ → **DTO_SINK**（違反候補）

GATE が 1 つでもあれば合格、PROPAGATE があれば保守的に合格（下流の enforce を否定できないため）、
DTO_SINK のみのときだけ違反候補とする（precision 優先の設計）。

検出器 javadoc の「既知の限界」（L124-128）はこの PROPAGATE を次のように明記している。

> **PROPAGATE は追わない**。下流メソッドが実際に enforce しているかは検証しない。
> よって「Controller が roleName を取り Service へ渡すが Service も素通し」という
> 2 段の抜けは**検出できない**（偽陰性）。下流の enforce は契約 IT で担保する。

つまり PROPAGATE は「委譲先メソッドの内部で実際に認可判定が使われているか」を機械的に
判定できないために保守的合格にしている箇所であり、**PROPAGATE = 認可漏れ確定ではない**。
下流で正しく enforce されているケースも、2段目で素通しになっているケースも両方含みうる。
本台帳はその**候補一覧**であり、個別の真偽判定（下流を実際に追う監査）は次工程（`AuthzPropagateInventoryTest`
の出力を種にした個別監査戦役）の役割とする。

## 測量方法

新設した棚卸しテスト
`backend/src/test/java/com/mannschaft/app/common/architecture/AuthzPropagateInventoryTest.java`
が、`AuthzGateEffectivenessAuditTest` の形②と**同じ判定基準**（GATE/PROPAGATE/DTO_SINK の
分類ロジック）で全 `src/main/java` を走査し、PROPAGATE に分類された箇所のみを収集する。

- 走査部品（マスク処理・ゲートクラス判定・メソッドパーサ・ゲート語彙収集・レシーバ識別子抽出）は
  `AuthzGateReturnValueGuardTest#mask` / `#isGateClassFile` / `AuthzGateEffectivenessAuditTest#parseMethods` /
  `#receiverIdentifiers` / `GateVocabulary` をそのまま流用（既存検出器は無編集）。
- GATE/PROPAGATE/DTO_SINK の分類判定式自体（`onlyFlowsIntoDto` 相当）は既存検出器側で `private`
  のため呼び出せず、同一の判定基準（javadoc L106-112 と同じ正規表現・除外条件）で
  `AuthzPropagateInventoryTest` 内に再実装した。
- package-private（アクセス修飾子省略）メソッドも認識できることを fixture で固定済み
  （既存検出器が過去に踏んだ偽陰性の再発防止）。

再生成コマンド:

```bash
cd backend && ./gradlew test --tests "*AuthzPropagateInventoryTest"
```

全件一覧はビルド生成物 `backend/build/reports/authz-propagate-inventory.txt` に出力される
（テスト実行のたびに再生成・コミット不要）。

## 実数

**未計測。** 本 PR の作成にあたり `./gradlew` の実行は方針上禁止されているため
（並行作業中の他セッションとの競合防止）、実測は行っていない。実数の確定は
`./gradlew test --tests "*AuthzPropagateInventoryTest"` の実行者（殿）に委ねる。

実行後、以下を埋めること（本節を実測値で置き換える）:

- 総件数:
- return形（`return v` / `return f(v)`）:
- 委譲形（小文字始まりメソッドへの引数渡し）:
- ドメイン別件数表（パッケージ第3階層 `com.mannschaft.app.<domain>` 単位）:

## 全件一覧

未計測のため空欄。`./gradlew test --tests "*AuthzPropagateInventoryTest"` 実行後、
`backend/build/reports/authz-propagate-inventory.txt` の内容（クラス名#メソッド名 /
ファイルパス:行番号 / PROPAGATE の型 / 委譲先メソッド名）を参照すること。
件数が数百件規模になる場合、本ドキュメントにはドメイン別件数表と上位のみを転記し、
**全件はビルド生成物のテキストを正本とする**（間引いた場合はその旨をここに明記する）。

## 自己検証 fixture

`AuthzPropagateInventoryTest` 内の `パーサ自己検証` ネストクラスに以下を同梱済み。

- `p`: `return v` 形が return形 PROPAGATE として検出されることを確認（陽性）
- `q`: 小文字始まりメソッドへの委譲が委譲形 PROPAGATE として検出されることを確認（陽性・委譲先名の記録も検証）
- `r`: DTO 構築のみに流れる形（DTO_SINK）が PROPAGATE として検出されないことを確認（陰性）
- `s`: `if` で打ち切る形（GATE）が PROPAGATE として検出されないことを確認（陰性）
- `t`: アクセス修飾子の無い（package-private）メソッド内の PROPAGATE も検出されることを確認
  （既存検出器が過去に踏んだ偽陰性パターンの固定）
