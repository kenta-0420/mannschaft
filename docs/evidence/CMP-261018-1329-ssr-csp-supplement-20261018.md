# 公開SSR/CSPとworktree棚卸しの追補（2026-10-18）

[元の完了証拠](CMP-261018-1329.md)を記録した後に、実装mergeの本番SSRビルド・起動と公開画面のnonce/CSPを追加確認した。元のSPA実機、nativeビルド中断、探索制限の記録と既存AC判定は維持する。本追補は公開画面に限定した検証であり、認証済みの実データSSRや本番HTTPSの合格を示すものではない。

## 対象と一次証拠

- 対象ソース: 実装PR [#3970](https://github.com/kenta-0420/mannschaft/pull/3970)のmerge commit `ba085e0c74bbad0b124ea282ed2a538b2c0a7d97`。完了証拠はPR [#3984](https://github.com/kenta-0420/mannschaft/pull/3984)で登録済み。
- 一次証拠のローカル保存先: `.claude/evidence/remaining-verification-20261018/`。SSRは `ssr/recon-report.md`、`merge-docker-ci.log`、`build-result.json`、`runtime-start.json`、`validation-r3/public-validation.json`、`validation-r3/validation-command-result.json`、`health-reproduction/result.json`。棚卸しは `worktrees/inventory-report.md`、`root-decision.md`、`latest-check.txt`、`windows-psutil-candidates.json`、`windows-psutil-candidates-final.json`。
- ローカル証拠はgit管理対象外。本書には対象SHA、実行結果、観測範囲と限界を残す。

## main CIの本番SSRビルド・起動

[Frontend CI run 39113075846](https://github.com/kenta-0420/mannschaft/actions/runs/39113075846)の[Docker job 117633126944](https://github.com/kenta-0420/mannschaft/actions/runs/39113075846/job/117633126944)は、対象ba085のmain pushでcompleted/successだった。標準Dockerfileの `npm run build` はキャッシュ済みではなく、Nitro成功と `.output/server/index.mjs` 生成、自己完結性チェック、実コンテナのHTTP応答成功をログで確認した。ビルド工程は568.7秒、Nitro工程は約379秒だった。

このCIはbackend無しの `/` の応答を検査する。nonce一致、hydration、ブラウザ操作、実backend接続の検証を含まない。imageは `push:false` / `load:true` でrunner内にのみloadされ、取得可能なartifactはbuild記録であり起動可能なimageではなかった。

したがって旧ローカル比較メモの「CIはNitro SSRを使わない」という一般化は適用できない。LighthouseのSPA generateと、main CIのDocker SSR build/startを区別する。旧メモ原本は保持した。

旧nativeビルド（cd630）はNitro生成中の正常TERMでactual exit -15のままで、成功へ読み替えない。CPU継続・OOMなしとCI側の長いNitro工程から、無出力時間だけでハングや製品障害とは判断できない。環境の処理速度差と整合するが、長時間化の原因は確定していない。

## 同じソースの本番SSR runtimeでの公開画面検証

ba085の標準Dockerfileを変更せず追加ビルドし、2026-10-18T12:46:56Zにactual exit 0、image `sha256:cecbea7a7a9197bc5ad242d799e83583dc8827a25da3332fb93b4214aae6fdbe` を得た。ビルド前後のソースハッシュは不変。SPA生成の指定やCSPを弱める変更は行っていない。

専有コンテナ `91a41c6f2a0428326003d47b8d4e047e20d9769ef465d08cbc5bd1739877fc56` を `127.0.0.1:13022` で起動し、HTTP 200を確認した。backendは接続せず、ブラウザ用APIは相対パス、server用APIは到達しない専用値とした。

最終r3のraw検証は `/` と `/login` を独立に各2回取得し、4応答すべてHTTP 200、JS実行前HTMLに本文またはログインform、Nuxtの `data-ssr=true` を確認した。各応答で実行可能script全件のnonceがCSPのscript-srcと一致し、リクエスト間でnonceが変化、placeholder残存なしだった。script-srcのunsafe-inline/unsafe-evalは追加せず、style-srcのunsafe-inlineは[正本](../security/03_security_headers_and_csp.md)§4.0の方針を維持した。

Chromium 148.0.7778.96では、公開LP表示、画面リンクからログイン、`login:hydrated`、PrimeVue Passwordの表示切替、空formの必須検証、forgot-passwordへのリンク遷移と固有フォーム表示・hydration後の送信ボタン有効化を確認した。JavaScript無効のcontextでもSSRログインformを確認した。load前から捕捉したCSP違反は0、既知vuedraggable eval違反も0、hydration警告・page error・request failureは0。最終r3はpass=true、browserClosed=true、actual exit 0で2026-10-18T12:53:31Zに完了した。実行helper SHA256は `c3b112796e015acd14246bb5564a24f2cb01deac66efc09547c015026ac9a718`。

初回r1はforgot-passwordの遷移完了待ち不足で画像が旧login画面だった。r2はreadonlyのフォント複製先を再上書きしてEACCESとなり、成功に数えない。r3で固有フォーム・有効ボタン待ちと、同一SHAの既存フォント再利用を行い再実測した。各試行の原証拠を保持した。

backend無しのため `active-incidents` のAPI 404が3件、`public/stats` の404が1件、Permissions-Policyの `web-share` 警告が2件観測されている。これらを含めてエラー皆無とは主張しない。公開画面でCSP違反0だったことを、未訪問の認証画面やvuedraggable利用画面へ一般化しない。

## 別件のDocker health不整合と専有環境の停止

公開HTTP/UIの成功とは別に、標準Docker healthcheckの接続失敗を確認した。同じ専有コンテナを再起動して再現し、`HOST=0.0.0.0` で待受は `0.0.0.0:3000` のみ、localhostの名前解決順は `::1` → `127.0.0.1` だった。コンテナ内の `wget http://localhost:3000/` と `[::1]` は接続拒否・exit 1、`127.0.0.1` はSSR HTML取得・exit 0、公開ポートはHTTP 200だった。背景APIの404とは別のTCP接続失敗である。

標準health設定（interval 30秒、timeout 10秒、startPeriod 15秒、retries 3）のまま、FailingStreak 1→2→3、`starting` → `unhealthy` を実測した。CIは公開ポートの応答成功を確認した直後に終了し、`Health.Status=healthy` を確認しないため本件を検出していない。調査時main `6d393636ea905c1fb4683e711488a8a8986a3ca6` のDockerfileも同じblob `8fa9ce58858950ff7e0f6ba85d03a18d75afb26a` で未修正。本追補では製品コードを変更せず、別件未解決として保持する。Issue本文案はローカルの `ssr/health-issue-body.md` にあり、現時点でIssueは未作成。

再現後は同じ専有コンテナを通常stopしactual exit 0、事後inspectでRunning=false・コンテナExitCode=0・OOMKilled=falseを確認した。2026-10-18T12:57:34Zの終了記録で、専有ポート13022への接続結果111（接続拒否）を確認した。imageとソースは保持し、他サービスは操作していない。

## worktree棚卸しと保持

追補文書用worktreeを作成する前の棚卸しsnapshotは総worktree 63、stale 42で、現在数を示さない。ROOTによる終了ゲート `node scripts/worktree-cleanup.mjs --days 1 --check --limit 60 --json` の実終了コードは2で、全体の片付けは未完了だった。撤去は0件。dirty、locked、未登録、junction、index.lock未確認、保護branch等の資産は保持した。

cleanな3候補 `agent-a089b6ce848e74196`、`agent-a86b6e5aacd8611bf`、`agent-af4365ddf0b4afe1f` は、棚卸し時の `origin/main` (`6d393636ea905c1fb4683e711488a8a8986a3ca6`) の祖先で、変更行なしだった。WSLの可視 `/proc` には候補のcwd/cmdline一致なし。初回Windows CIM照会はタイムアウトした。

その後Windows psutil 7.2.2で読み取り確認し、不可視PID/image名を加えた2026-10-18T12:50:31Zの再確認は658プロセス中、cmdline読取650・AccessDenied 8、cwd読取635・AccessDenied 23で、読取可能な範囲の候補名一致は0だった。不可視対象はsystem/security系主体だが、image名空のPIDやvmmemWSLも含む。不可視プロセス・間接参照・将来の使用を除外できず、3候補とも削除していない。これは全体clean合格の根拠ではない。

本陣の既存変更 `.claude/commands/アリシゼーション.md` と `docs/prototypes/beta-inventory-board-data.js` は保持する。

## 残る検証範囲

認証済みSSR、実backendデータを使う操作、ロール横断、CSP report-uriの実backendへの収集、本番HTTPS・エッジ・HSTSは今回の対象外。元のSPA/実BE実機と同じ範囲をSSRで再実行したとは主張しない。Docker health不整合の修正、旧native長時間化の原因確定、全体worktree終了ゲートの解消も未完了のまま残る。
