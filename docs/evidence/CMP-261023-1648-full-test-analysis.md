# CMP-261023-1648 全件未完走の分析と追加改善

## 保存原本と判定

2026-10-23 の全量実行は完走していない。原本は
`.claude/runtime/l0m-resume-261022-1756/final-test0-2258/` の
`host-receipt.json`、`process-result.json`、`native-result.json`、`gradle.log`、
`stopped.json` に保存している。揮発 runtime は解析資料であり、全件成功の証拠にはしない。

実行条件は通常テストの並列 fork 1、`fork.every=180`、migration 除外なし、
timezone `Asia/Tokyo`、offline、daemon、build cache 無効、`--rerun`、
Gradle worker 上限 2 だった。Windows QPC の経過 42170.9269301 秒
（約11時間43分）で期限停止し、exit 143、`timed_out=true` となった。
通常 `test` の結果 XML は 0 件で、残りのテスト数も確定していない。
ArchUnit・Integrity の結果 XML とは分けて扱う。未完走を成功や所要時間の上限とは扱わない。

## 長時間化について分かったこと

- 通常テスト worker は累計32 JVM。別途 ArchUnit 52 JVM と Integrity 1 JVM が起動した。
  通常 fork 1 は同時実行数の指定であり、全工程を1 JVMで走らせた意味ではない。
- ArchUnit とその依存処理は開始後約6分40秒までに終わっている。
  そのため、後続の長い通常テスト実行を ArchUnit のみに帰属させる根拠はない。
- 採取スタックでは Flyway と metadata の初期化処理が観測された。
  DB/schema と Spring context の再初期化は改善候補になるが、採取時点だけの観測から
  全時間の寄与率は計算できない。
- 詳細出力は約1 GBに達した。ログI/Oも候補だが、I/Oを分離した比較がなく寄与率は不明である。
- 最終ログ上の失敗は計4件。失敗と長時間化は別の事象として扱う。
  公式fixtureの改行変換によるraw SHA不一致は独立した確定不具合として修正する。
  その他の失敗の具体名・値は本記録の公開範囲に含めない。

独立したfixture生成長の不備も現行sourceに残っていたため、期限切れ招待の既存ケースで
実行前に生成するtokenを、製品と同じ36文字のUUID形式へ直す。
既存ケース自体を回帰テストとして保持し、期限条件・期待404・認可検証・製品・DDLは変更しない。
保存済み旧runのassert到達前の列長エラーと現行sourceの一致を修正前の根拠とし、
修正後の既存ケースの成功は最終headの通常CIで確認する。ローカルでは未実行である。

時計も分けて評価する。host UTC と QPC は 1.7864038 秒ずれ、native の
realtime と monotonic は約2652.4秒ずれている。QPC はWSL起動を含む正本時計だが、
`process-result.json` は時計差、daemon 照合、receipt の整合性により `valid=false` と判定した。
これらは計測の妥当性を制約する要因であり、テストが長時間化した原因と同一視しない。

## 追加改善の範囲

Team と Organization の可視性 Resolver は常時登録されるため、両結合テストの
`feature.visibility-resolver.*=true` は Bean 有効化に不要である。
この2つのローカル `@SpringBootTest` 宣言だけを取り除き、Survey と同じ
`AbstractMySqlIntegrationTest` の共通構成を継承する。
共通基底・profile・外部依存mock・MySQL tmpfs・schema生成設定は変更しない。
既存の `@Transactional`、Docker条件、fixture、Team 4件・Organization 3件・Survey 9件の
全16テストケースと全assertionを保持する。

先行回帰テスト `VisibilityResolverContextConfigurationTest` は、3つの実ITを
`Class.forName(..., false, ...)` で読み、`BootstrapUtils` が生成する
`MergedContextConfiguration` の集合が1種類であることを検証する。
ApplicationContextのloadや対象クラスのstatic初期化を伴わない方式は、既存の
`R2MockContextConfigurationTest` に合わせる。追加のDBやmock構成は作らない。
この変更で狙う構成keyは3種類から1種類への削減であり、秒数の改善は実測前には断定しない。

## 検証と残る限界

ローカルGradle/Docker起動は行わず、先行テストのredと修正後のgreenを専用remoteで検証する。
同じrunner・同じ実行条件・事前warm条件で初期化込みwallとSpring起動回数を単回ペア比較し、
対象SHA、開始時の他heavy処理の有無、全16件pass・skip 0を記録する。
metadata回帰のgreenだけで実MySQLの認可・rollback検証を代替しない。
通常CIと専用検証は最終実装headを固定して行い、実測結果は後続の完了記録へ保存する。
公式fixtureのLF固定は別PRで検証し、この実装PRへ混ぜない。

既存のPayer改善は対象14件の成功と対象単回約37%の短縮という範囲の証拠である。
その割合を約11時間43分の全量や今回の3ITへ外挿しない。
今回も全量の完走時間・改善率は未測定のままであり、旧CMP-261004-2047の全体完了を意味しない。
