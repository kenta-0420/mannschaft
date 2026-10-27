# 日時型・API宣言の局所再検証

## 根因と修正範囲

- `.claudecode.md` §20はEntityにLocalDateTimeを求める一方、日時正本 `docs/architecture/datetime_policy_utc_instant_vs_wallclock.md` §1/4は新規の瞬間にInstantを求め、§6で既存規約との矛盾を明示している。矛盾をユーザーへ報告し、新規2fieldだけ新規コードの明示方針と現行番人に従う実装判断をした。全体規約は変更していない。
- 表示設定のcreatedAt/updatedAtをInstantへ戻した。既存Ranch/Diagnosisの新規Entity同様Hibernate標準マッピングを使用し、専用converterは追加していない。DB DATETIME(6)、JDBC UTC設定、native UPSERTのUTC_TIMESTAMP(6)、既存ID/createdAt保持、日時を公開しないAPI契約は維持した。
- 既存CommitITの成功caseへ、DBのUTC文字列とEntityのcreatedAt/updatedAtのInstantが同じであることを2assertion追加した。日時凍結台帳への追加はない。
- TEAM/ORG表示設定のGET/PUT計4methodへ、一般表示判定の取得と全非表示からの復帰に必要なGATE_CONTROL_PLANEの常時到達宣言を理由付きで追加した。既存の所属・ADMIN/SYSTEM_ADMIN認可は変更していない。

## API scannerの原因実測

- 既存自己試験はclass-level RequestMappingをHTTP methodへ数えない契約。新ControllerのネストしたApiResponses内で、annotation正規表現が最初の閉じ括弧まで消費した後のoffsetを渡し、残りのSwagger注釈の開き括弧をmethodへ誤帰属していた。
- 追加した `nestedSwaggerAnnotationsDoNotCreateMappedMethods` を元scannerで実行し、期待HTTP2件に対し実際3件のassertion失敗を確認した。同じ自己試験は、既存の括弧深度scannerへ注釈名直後から渡す2箇所のoffset補正で成功した。javacの局所診断であり、最終結果は下記正規Gradleでも確認した。
- 元の固定c6431d71d8のproduction全体に対するscanner比較は3731→3729。変化は今回のTEAM/ORG Controller各3→2だけで、既存全classは不変。正当な増分は既存3725＋新4APIである。
- HTTP期待値は3729、新2classだけ未宣言0/総数2として台帳へ追加した。既存未宣言枠は増やしていない。AC145の実走査class数と台帳行数は動的比較のため、そのコードを変更せず新class2件を反映して787で一致する。

## 正規Gradle・実MySQLの結果

- 固定HEAD: `371fcc7150ccdae8c76a2fb4886762649e6222af`。専用clean Linux cloneへ指定SHAだけlocal fetchし、verification-runで隔離展開した。共有Gradle turnstileを取得し、1forkで実行した。pushは担当していない。
- 対象: ContractIT 30、CommitIT 3、ServiceTest 5、ApiGateDeclarationGuardTest 8、DateTimeAndZoneGuardTest 3、BillingPlanChangeGuardExistenceRedITのAC145 1。
- 合計 **50実行・50成功・失敗0・error0・skip0**。既存機能の38件、UTC同一瞬間の追加assertion、ネストSwagger自己試験、API総数/全class台帳比較、日時番人、AC145メタ番人がすべて成功した。前提のarchUnitFreezeStoreIntegrityTestも2件成功した。
- `BUILD SUCCESSFUL in 9m 50s`、exit0。XML6ファイルを照合済み。receiptのsourceHeadは上記固定SHA、cleanupはremoved。
- receipt/XML: `/home/kenta/verification-evidence/CMP-261025-2202-be-guard-green/`。
- 親もXML6件とreceiptのsourceHead・exit0・cleanup removedを独立照合し、[永続する集計結果](architecture-local-results.json)へ保存した。raw XMLのstdoutや環境変数は含めていない。
- 実行: `gradlew test` に上記6対象の完全修飾名（AC145のみmethod絞込）を指定し、`--no-daemon -x archUnitTest -x jacocoTestReport -Pfork.every=0 -Pmax.parallel.forks=1`。verification-runとturnstileを経由した。全量CI・最終出荷判断は親担当。
- 診断と実行補助の一時スクリプトは担当が作成した正確な4pathのみ削除済み。verification-runの管理領域検査も残存scratchなしで終了した。既存FE/BE、実機ログイン、他担当の証跡は操作していない。
