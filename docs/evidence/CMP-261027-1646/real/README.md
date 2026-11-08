# L1-B 実機・探索の最小証拠候補

対象 source HEAD: `a8b7d8b999973d2c138ffecce2fbe2233f6bb09a`。rootがJSONと代表PNG3枚を確認した最小パッケージを完了文書原稿へ採用した。今回の整理による追加試験・原本変更・削除は無い。統合HEAD f281で再実施したとは扱わず、[証拠入口](../README.md)のsource bridgeと最終統合CIを別に判定する。

`evidence.json` は原本から必要項目だけを抽出し、HTTP状態や観測件数を集計した。`provenance.json` に原本の正確path・bytes・SHA256と画像コピーを対応付ける。アカウント名・メール・資格値・Cookie値・fixture内容を収録しない。URIのquery/fragmentは除外した。

## 実機4case

通常UIログイン、実BE18082/FE18083、専用DB、CI profile、mock/route/認証ヘッダー注入なし。純ADMINのteam/org管理許可、MEMBERのteam管理拒否、ADMINの非所属org管理拒否、匿名login遷移を確認。管理拒否API403と画面遷移をJSONに保持する。4caseとteam/orgの画面2枚を混ぜてcase数を増やさない。元foreign拒否PNGは遷移直後loadingで、安定移動先の証明ではない。

## 独立した3住民

- admin（一般・素直）: dashboard→team管理→メンバー一覧→所属org管理→帰還。console/requestfailed/HTTP error 0。初期設定dismissは保存PUTとは別。画像なしのため視覚確認済みとはしない。
- member（ADHD傾向・中断復帰）: 予定画面から別ページ/戻る、Q&A別タブ/復帰、活動詳細/別タブ/復帰を確認。表示月に予定項目がなく個別予定詳細の復帰は未検証。
- third（一般・隙間狙い）: 非所属team/org管理URL直打ち後、公開scopeへ戻りloading消失後に基本情報操作成功。modules403/console403は拒否として記録。toastは採取時点未観測で、実機のtoast観測とは区別する。

## 失敗前段と限界

初回npm準備失敗、実機後SIGHUPによる接続拒否時点の探索、復旧後初期timeout/支援driverのselector missは成功回に含めない。同scratchをroot管理で復旧し、seed/login再実行なしのreadiness確認後、各住民が独立ログインして完了した。実機の速い遷移時Failed to fetchは安定admin/member探索では継続再現しなかったが原因未確定。確定不具合0は今回範囲に限る。billing署名鍵未設定はfail-closedであり課金成功を主張しない。全画面/全viewportの視覚監査、性能、最終CI/mergeの証拠ではない。

## 停止・撤去

全browser終了後root stop-requestでstillLive=[]。旧SIGHUP receiptは原本のまま残し、root-recovery-cleanupのremovedで解決を証明。自隊scratch、専用MySQL/Valkey、専用volume/npmtools、不要runtime2WTは撤去済み。共有browser/Gradle cacheと実装関連WTは保持した。JSONは当時の撤去証明からの抽出であり、今回再起動や再撤去をしていない。

## 代表PNG候補

`anonymous-denied.png`、`member-schedule.png`、`third-org-denied-stable.png`の3枚。2026-10-28に画像そのものを確認し個人名/メール/資格値がないと判断し、rootも確認して採用した。テスト団体名は表示される。匿名画像は原本で入力欄がmask済みであり、入力値やログイン成功を証明しない。member画像は中断前、third画像はorg管理拒否後の安定画面。admin成功画面や活動復帰画像の代用ではない。

## 集計snapshotの差

memberの住民報告/統合報告は200×183/204×2、保存原本responsesの今回再集計は200×188/204×2。差5件の発生時刻/因果は未確定で、同一snapshotとは扱わない。JSONにこの差を明記し、観測URLの順序と重複APIのcountも保存した。
