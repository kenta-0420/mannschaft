# 全量CIで検出した宣言・日時型の是正

## red（2026-10-26）

head `c6431d71d8bb8371b9f68fada63b4a3f7fbc5c22` の [Backend CI shard4](https://github.com/kenta-0420/mannschaft/actions/runs/40224215370/job/121262279595) は5,832件中2件失敗、2件skip。環境起動失敗ではなく、次の既存番人のassertionへ到達したredである。

- `ApiGateDeclarationGuardTest`：HTTP mapped method走査総数の期待3,725に対し実3,731。新APIの宣言・走査内訳を確認し、未宣言の凍結枠を増やさず是正する。
- `DateTimeAndZoneGuardTest`：新規`ScopeFeatureDisplayPolicyEntity`のLocalDateTime型フィールド2件を禁止。作成・更新は記録した瞬間なので、新規コード方針に沿ってInstantとする。

同headの[shard3](https://github.com/kenta-0420/mannschaft/actions/runs/40224215370/job/121262279624)は5,056件中1件失敗、2件skip。「PR6b-1 番人」のAC-145（API宣言のクラス数と台帳行数のメタ検査）が期待785に対し実787となった。新Controller2クラスを未宣言枠へ逃がさず、明示宣言と正しい実測値へ同期する。

## 走査原因の切り分け

既存自己試験はclass-level `@RequestMapping`をHTTPメソッドへ数えない契約である。新Controllerのネストした`@ApiResponses`に対して、annotation正規表現の末尾から後続メソッドを探索すると、内部`@ApiResponse(...)`の括弧へ誤帰属していた。既存のbalanced括弧走査へ注釈名直後から渡す局所修正で、合成検体は修正前3件（期待2）のassertion redからgreenへ変わった。

全production sourceで同じ走査器を比較し、3,731→3,729、変化は新TEAM/ORG Controller各3→2だけ、既存全クラスは不変と確認した。正当増分は4メソッド・2クラスである。未宣言数の凍結枠を増やす理由はない。正規Gradleと実DBでのgreenは修正commitで別途確認する。

旧`backend/.claudecode.md §20`のLocalDateTime・Instant不使用と、`docs/architecture/datetime_policy_utc_instant_vs_wallclock.md §1/4`の新規瞬間型方針には実際の矛盾がある。日時方針§6自身もこの食い違いを記載している。ユーザーへ報告し、今回の新規2列は新規コードの明示方針と現行番人に従う。旧規約の全体改訂・既存の全域移行・凍結台帳の緩和は本対応に含めない。

初期検分で既存実装へ合わせてLocalDateTimeへ変更した判断を撤回する。DBのDATETIME(6)・UTC格納とnative SQLのUTC_TIMESTAMP(6)は維持し、APIの表示設定bool契約を変えない。修正後の実DBと番人のgreen、最新headの全量CI成功を確認するまで完了判定しない。

## 局所green

修正head `371fcc7150ccdae8c76a2fb4886762649e6222af` の正規Gradle・実MySQLで50件成功、失敗/error/skipとも0。既存Contract/Commit/Serviceの38件と、API宣言番人8件、日時番人3件、AC145メタ1件を含む。[局所実行記録](architecture-local-validation.md)と[XMLの独立集計](architecture-local-results.json)を参照。親も原本XML6件・receiptのhead/exit0/cleanup removedを照合した。

実機後の本修正は日時の内部読取型、APIの制御面宣言、番人と回帰テストに限定する。画面・表示boolのHTTP契約・認可・保存SQLは変更していない。日時型の影響はUTC DB値とInstantの一致を含む実MySQLの38件で検証し、前段のTEAM/ORG実機・3住民の画面検証を保持する。最新headの全量CIは[Backend run 40232982194](https://github.com/kenta-0420/mannschaft/actions/runs/40232982194)で再実行中であり、まだ成功扱いにしない。

初回全量の正式ログでもContractIT・CommitIT・ServiceTest、API宣言番人・日時番人・AC145を含むメタ番人の6クラスすべてがSUCCESSで終了したことを親が独立照合した。[正式CIのクラス結果](backend-feature-ci-results.json)を参照。初回全体は別の既存MFA性能2件で失敗しているため、この6クラスの成功を全体成功の代わりにはしない。再検証経過は[性能テストの切り分け](ci-performance-validation.md)に記録する。

## 最新統合headの正式成功

実装head `609ee90bf65af64b17d95e9ee31d27953c5abdaa` の [Backend CI 40246540422](https://github.com/kenta-0420/mannschaft/actions/runs/40246540422) は全6shardと集約gateがsuccess。親がjob APIと6分割の原本ログを照合した。[最新対象クラス・性能値](backend-final-ci-results.json)を参照。Contract/Commit/Service、API宣言・日時・AC145メタの6クラスはいずれもSUCCESS。旧失敗・途中cancelledは履歴として保持し、最新成功と区別する。
