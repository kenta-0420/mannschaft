# CMP-261021-0647 完了検証記録

実装head **82d8682768de515d72827e868eac65c11d2d1324**、[PR #4053](https://github.com/kenta-0420/mannschaft/pull/4053)。rootが対象CMP0647のreview/real/exploratoryをpassed承認し、runtime正常終了を確認済み。PR4053は2026-10-25T04:26:23Z通常squash merge（bfd83ff311b15602085f8a4003c080cb0d564ca4）。実装headの証拠は82dのまま保持する。

## 受け入れ条件と証拠の世代

| 条件 | 実行source/証拠 | 証明範囲 |
|---|---|---|
| AC-1 既定候補/正slug | 旧caller red→green＋82d固定UI | 旧試験はAPI route mock。82dは実BEの正slug members/all GET1回・候補6・選択 |
| AC-2 既定往復 | 7f実UI3modeと独立DB APPROVED | 82d新規往復ではない。不変な主要業務経路への限定対応/root最終承認済み |
| AC-3 失敗理由/再試行 | 82d genuine offline→online | 実GET requestfailed/理由/送信disabled→UI retry GET200/6候補。page.route/API代替なし |
| AC-4 認可 | 82d独立Context/実ログイン | 権限あり/SUPPORTER/他組織/未認証、画面/直URL拒否と非公開タイトル非露出 |
| AC-5 表示/文言 | 067既存3キー×6locale→82d actualUI | TEMPLATE重複除去、OPEN_CALLチーム内募集、guide整合、360/390/header/help、overflow0 |
| AC-6 検分/CI/探索/終了 | 下記 | root全stage passed・正常cleanup完了。実装PR merge確認済み |

[証拠JSON](CMP-261021-0647.json)はroot最終判定で全3stage passed。構造/参照検証とGitHubのmerged実装PR照合を分離し、merged実装PRと同headの4CIを正式APIで別途照合する。

## 正式CIとBEキャッシュ境界

rootが2026-10-25 04:06 UTCにPR head82d/CLEAN、全checks成功または許容SKIPを直接確認。4run全SUCCESS。

- [Backend 40158601318](https://github.com/kenta-0420/mannschaft/actions/runs/40158601318)
- [Frontend 40158601304](https://github.com/kenta-0420/mannschaft/actions/runs/40158601304)：実npm audit・audit回帰・lint・typecheck・unit成功。
- [E2E 40158601308](https://github.com/kenta-0420/mannschaft/actions/runs/40158601308)
- [Lighthouse 40158601261](https://github.com/kenta-0420/mannschaft/actions/runs/40158601261)

82d BEは6shardともFROM-CACHE/fresh測定0。artifactのcompleted=false/testStepExitCode=nullとcommand/shell exit0を区別する。現head CI successは実結果だがfresh全量実行とは書かない。[root集約](CMP-261021-0647/root-be-ci-82d8682-readback.json)。同backend tree 9790002753834502d8680e7d641a5905049f6ccd の[a38 fresh run40150974359](https://github.com/kenta-0420/mannschaft/actions/runs/40150974359)は全6成功、正規化top-level3085/重複0/重み一致3083/fallback2。旧raw8303をトップレベルclass数と数えない。

原初MFA latency失敗の直接原因は未解明。greenで原因解明済みとはしない。閾値・暗号caps・試験集合変更0。forge GHSA-86w9-cpqp-85rvはユーザー明示裁可による2026-10-31T00:00Zまでの既知High例外。braces延長なし、未知High/Critical拒否維持、脆弱性修正済みとは称さない。

## exact buildと82d固定読取実機

[root exact build照合](CMP-261021-0647/root-build-82d8682-success-readback.json)：JAR221046571B/SHA256 d772c0e02462e9cc36eb48a82da90ca74dd5fc8bcf0304c00a4d37a9773baf49、manifest13202件/SHA25620e4d7498be7ab8a9b181a8aa7aa74f113a48cad61dc8ae05ae715e09949278a。build testsExecuted=falseと正式CIを分離。localhost専有検証だけで本番作成/deploy/外部公開0。

[固定UI root検分](CMP-261021-0647/root-ui-review-82d-0334.json)：18PNG実視認・console分類、actual CSP違反0/pageerror0/overflow0、source19134前後同値/監視DBsnapshot同値/業務書込0。POST全0ではなく自動error-reports3件がある。認証refreshは業務申請と区別する。root UI検分原本のci/audit falseは当時の固定入力で、後の正式CI成功へ書換えない。

代表画像は[同bytes/SHA保全manifest](CMP-261021-0647/preservation-manifest.json)。18枚全部を登録せず4枚に絞った。

- [offline実失敗](CMP-261021-0647/images/specific-members-offline-390.png) / [UI retry成功](CMP-261021-0647/images/specific-members-retry-390.png)
- [TEMPLATE本文](CMP-261021-0647/images/template-390.png) / [チーム内募集](CMP-261021-0647/images/open-call-390.png)

文言修正は旧a38実DOM再現→067修正→82d actualUI exact assert。これを自動red先行と称さない。

## 歴史roundtripの限定適用

[旧実UI/DBの安全抜粋と原本hash](CMP-261021-0647/historical-ui-roundtrip-proof.json)はsource7f924ae77888926463b34145aeeda7e77183b7fcの3mode PENDING→ACCEPTED→APPROVED・独立DB照合・正常cleanup。API補完15件（正slug成功1＋拒否14、拒否前後DB不変）は別。UI入口/役割横断をAPI直叩きへ置換しない。

7f→d146はBE/FE tree同一。d146→d8→0a→a38の主要shift状態遷移/DTO/作成本人・PUBLISHED制限/候補GET/承諾承認callerを追跡した。[root限定適用](CMP-261021-0647/root-historical-roundtrip-applicability.json)・[依存readback](CMP-261021-0647/root-roundtrip-dependency-readback.json)。lockにZod3→4/adapter削除、DOMPurify 3.4.16→3.4.17、happy-dom等の変更があり、全入力同値ではない。フォームはcomputed canSubmit、APIはuseApi/ofetchでZod/VeeValidateを直接使用しない。a38→067は6locale既存3キーのみ、067→82dはdocs3pathのみでapp tree同一。新依存/文言での入口・候補・表示・認可・通信障害復帰を82d readonlyUI/CIで別検証する。82d新規APPROVEDの実行証明とはしない。

## 回帰red/green

[実red](CMP-261021-0647/slug-caller-red-proof.json)：2026-10-22 01:14Z、数値ID候補URLへのURL assert不一致failed1。[green](CMP-261021-0647/slug-caller-green-proof.json)：01:22Z、同caller passed1/retry0。原本hash独立照合、安全な選択情報だけ追跡保全。各runの独立exact実行commitは未記録、receipt base/headを代用しない。API route mockを使うため実BE認可/永続化証拠ではない。

## 新3住民と最終正常終了

[探索記録](CMP-261021-0647/exploratory-evidence.json)。root最新報告ではgeneral id50最終/my、mobile id38最終/my（360/390・候補末尾選択・3方式理解）、ADHD root id34finish（中断復帰/cancel達成）。ADHD最後のmy戻り未確認。general18/19・ADHD32の未確定応答と前command終了前送信の手段逸脱は達成証拠に数えない。viewport近似をnative zoom実測とはしない。agent report未更新欄はroot補足と区別。全session-final業務mutation0/GETまたはauthrefreshのみはroot確認。交代依頼の作成・承諾・承認・取消の業務API新規実行0（ダイアログを閉じる操作は実施）。

[root最終判定](CMP-261021-0647/root-final-quality-82d8682.json)で対象CMP0647の全stage passed。[正常停止compact証拠](CMP-261021-0647/normal-cleanup-proof.json)：runtime exit0、before/after/final監視DBsnapshot完全一致、source19134全3一致、全PID終了/newBE通常停止削除/DBcache停止/ports0/scratch removed/error0。実装PR merge後の完了文書登録として扱う。別件mobile既存32pxは独立課題へ分離しCMP0647導入blockerと混同しない。CMP260908-2116/0648等の状態は変更しない。

歴史適用root原本のpending/currentHead=a38は当時の状態を保持したもの。今回82dへの最終採否はroot-final-quality-82d8682.jsonのAC2限定継承承認に従う。

## 本文登録ゲートの履歴

最終本文更新で対象CMP-IDが欠落し、Task-List Gate [40161583580](https://github.com/kenta-0420/mannschaft/actions/runs/40161583580)はfound0で失敗した。本文先頭へCMP-261021-0647を復元し、正本regexでtitle+bodyのユニークID1件を検証。通常body編集による新[40161716441](https://github.com/kenta-0420/mannschaft/actions/runs/40161716441)はsuccess。workflow再dispatch/迂回/adminは使わず、旧失敗も履歴として残す。

## 正式完了ゲート照合

[公式API照合](CMP-261021-0647/completion-api-validation.json)：PR4053 merged/head82d/merge bfd/mergedAt04:26:23Z、changed_files29と取得件数一致。正本summarizeImplementationChecksでマージ時点の世代を選びsuccess/許容skipだけ、同head4CIは全completed/success。正本validateEvidence/validateCompletionChangesへ実API値を渡し、構造/全参照/app全stageを照合した。APIだけで実機内容の真偽やbranch protection必須名完全一致を証明するものではない。
