# 停滞している OPEN PR の棚卸し（CMP-260826-1041）

**実測日**: 2026-08-26 / **対象**: `gh pr list --state open --limit 80` の全42件

**2026-10-05 照合**: 初版が列挙していたのは31件だった。CMP IDの時刻（2026-08-26 10:41 JST）を基準に、作成・閉鎖・再開・マージ履歴から当時OPENだった未掲載11件を補完し、42件を再構成した。保存済みの公式GitHub状態で重複0件、**MERGED 26件 / CLOSED 11件 / OPEN 5件**を確認した。10月3日の前回記録から新たにMERGEDとなった7件の日付とmerge SHAを反映した。元の規模・状態・停滞日数と既存の人手判断は当時の記録として残す。

**2026-10-30 再照合**: #1316（CLOSED 10-27）・#1489/#1490（CLOSED 10-06）・#3607（MERGED）を確認。残るOPENは #1502（マスター裁可により閉じず後回し）と #1317 のみ。後継の依存更新PR（#3683・#3685・#3687・#3695・#4174）の調査結果は下記「後継の依存更新PR」節に記録した。

**CMP-260826-1041 は未完了**。残る処遇対象は本番インフラ #1502（後回し）と依存更新 #1317（および後継PR群）で、殿の判断が残る。MERGED/CLOSEDの確認だけで、復活時のCI・実機や本番構築を実施済みとは扱わない。

放置された PR は静かに腐る。特に `DIRTY`（コンフリクト済み）は **CI が一度も走らない**ため、
緑にも赤にもならず「動いているように見えて実は死んでいる」状態になる
（`feedback_pr_conflicting_means_no_ci_runs`）。本表はその棚卸しであり、
**1行ずつ「復活させる／閉じる」を判断して消し込む**ための台帳である。

## 判断の作法

- **中身を読まずに閉じてはならない。** 「500エラー根治」「認可の点火」など、
  閉じると欠陥が残り続けるものが混ざっている。
- 復活させる場合は、その PR のブランチで worktree を切り、main を取り込んでから CI を回す。
  **本陣の作業木で直接触らないこと。**
- 閉じる場合は、なぜ不要になったのか（別 PR で解決済み・方針変更等）を PR コメントに残す。
- 消し込みは本表の「処遇」列を埋めて更新する。

## 人が書いた PR（bot 以外）

| 停滞 | PR | 規模 | 状態 | 内容 | 処遇 |
|---|---|---|---|---|---|
| 89日 | #1170 | 1f | BEHIND | シフト管理画面を権限別表示に対応 | **復活・マージ済**（2026-08-26 / `2ec2416c3`）。残る BE 側絞り込みは CMP-260826-2127 へ |
| 89日 | #1169 | 3f | DIRTY | タイムライン投稿APIを FormData→JSON に修正（**500エラー根治**） | **クローズ**。main の `CreatePostRequest:118` に `@JsonCreator` 実在・FE も JSON 送信済み |
| 89日 | #1168 | 4f | DIRTY | タイムライン画像アップロード用 Presigned URL API | **クローズ**。`TimelineAttachmentController:53` に実装済み（URLパスのみ差異） |
| 89日 | #1167 | 2f | DIRTY | フィードAPIのレスポンス型をネスト構造に修正 | **クローズ**。`TimelineFeedResponse` として同一構造が main に実装済み |
| 87日 | #1209 | 8f/+520 | DIRTY | **認可 Phase2 点火 — `@EnableMethodSecurity` 有効化**。現 main で既に有効化済みか要確認 | **クローズ**。点火は `SecurityConfig.java:41` に生存。回帰テスト不在は CMP-260826-2128 へ |
| 81日 | #1361 | 1f | BEHIND | TODOビュー切替ボタンのスタイル | **復活・マージ済**（2026-08-27 JST、[PR #1361](https://github.com/kenta-0420/mannschaft/pull/1361) のMERGEDを確認） |
| 73日 | #1502 | 1f | BEHIND | 本番インフラ初回構築（mannschaft.app） | **OPEN のまま閉じない・後回し**（マスター御下命、2026-10-30 時点で OPEN・最終更新 2026-06-13）。`infra-apply` 全件 skipped ＝ 未構築の疑い。CMP-260826-2129 へ |
| 68日 | #1630 | 4f/+575 | DIRTY | 組織配信の再帰的配下解決（WITH RECURSIVE・depth32） | **クローズ**。main が keyset/sharded まで発展済み・当てると退行 |
| 61日 | #1877 | 1f | BEHIND | 目安箱フィードバック投稿UIの実機E2E | **復活・マージ済**（2026-08-27 JST、[PR #1877](https://github.com/kenta-0420/mannschaft/pull/1877) のMERGEDを確認）。specのマージと実ブラウザ実行を区別する |
| 58日 | #1964 | 8f | DIRTY | 予定編集モーダルのリマインダー/繰り返しUI重なり修正 | **クローズ**。同修正が着地済みで main が上位互換 |
| 57日 | #1995 | 2f | BEHIND | 活動記録「記録を追加」作成フローの実機E2E | **復活・マージ済**（2026-08-27 JST、[PR #1995](https://github.com/kenta-0420/mannschaft/pull/1995) のMERGEDを確認）。specのマージと実ブラウザ実行を区別する |
| 48日 | #2207 | 2f | DIRTY | ベータ特典条項（第27条）の条文案・6言語対訳 | **クローズ**。main の条文が 2026-07-29 に改訂済・当てると退行 |
| 45日 | #2260 | 3f/+872 | BEHIND | WS外部ブローカー化戦役の実機スペック3本 | **復活・マージ済**（2026-08-27 JST、[PR #2260](https://github.com/kenta-0420/mannschaft/pull/2260) のMERGEDを確認）。specのマージと実ブラウザ実行を区別する |
| 38日 | #2353 | 62f/+6411 | 復活対応中 | 承諾型招待＋オーナー委譲承諾型化（F04.12）。最新 main へ追従し、規約・OpenAPI・テストを補正して PR を再開 | **復活・マージ済**（2026-08-29 JST、[PR #2353](https://github.com/kenta-0420/mannschaft/pull/2353) のMERGEDを確認） |
| 21日 | #2559 | 4f | DIRTY | E2E storageState 不在時の ENOENT クラッシュ根治 | **復活・マージ済**（2026-08-27 JST、[PR #2559](https://github.com/kenta-0420/mannschaft/pull/2559) のMERGEDを確認） |
| 11日 | #2768 | 28f/+3329 | DIRTY | 単独チームの試合記録を開始可能に | **クローズ**。main に V185 として着地済・当てると migration 二重適用 |
| 10日 | #2826 | 13f/+839 | DIRTY | アンケート公開時の対象人数スナップショット（CMP-042） | **復活・マージ済**（2026-08-28 JST、[PR #2826](https://github.com/kenta-0420/mannschaft/pull/2826) のMERGEDを確認） |

## dependabot の PR

| 停滞 | PR | 内容 | 処遇 |
|---|---|---|---|
| 56日 | [#1079](https://github.com/kenta-0420/mannschaft/pull/1079) | jjwt-jackson | **マージ済**（2026-10-04T23:51:21Z / 108e2c3206e8eca366c4d1ddbcd4c1aadeec6557） |
| 56日 | [#1078](https://github.com/kenta-0420/mannschaft/pull/1078) | actions/checkout 4→7 | **マージ済**（2026-10-03T22:27:12Z / 81ca6c1520d77b44e914a3e490039740afc9a211） |
| 56日 | [#1077](https://github.com/kenta-0420/mannschaft/pull/1077) | jsoup | **マージ済**（2026-10-03T20:12:14Z / fe3db9b8aca656baee4a25df86f3d525f02b46e6） |
| 56日 | [#1076](https://github.com/kenta-0420/mannschaft/pull/1076) | github-script 7→9 | **復活・マージ済**（2026-10-03 JST、`479c60b1dcdf96c8f86071e69a51f0ee47f27cad`）。[実APIコメントrun](https://github.com/kenta-0420/mannschaft/actions/runs/37062378940)のScan & CommentとPost or update PR commentが成功。Terraform Planはskippedで、本番Infra実行済みとは扱わない |
| 56日 | [#1074](https://github.com/kenta-0420/mannschaft/pull/1074) | cache 4→5 | **復活・マージ済**（2026-10-02 JST、`cdf4a31972f98c250ed57b2ce0bbf724984550ff`）。GitHub primaryのMERGEDとmerge SHAを確認 |
| 56日 | [#1073](https://github.com/kenta-0420/mannschaft/pull/1073) | poi-ooxml | **マージ済**（2026-10-04T01:59:10Z / 53f80e2e5238e7cebc56d3dd60876d31236b2ef1）前回照合（2026-10-03）時点の記録: 当時head `ffffae5ef5115d3bd00d24024a4d76ed8a014742`の対象27件はfailure/error/skip各0。[全体CI run](https://github.com/kenta-0420/mannschaft/actions/runs/37009214808)はArchUnit importerのJava heap spaceで失敗し、永続化enum番人もskip。当時は未マージであり、共通対策PR #3607（MERGED、2026-10-03T15:38:34Z）の全6シャードと全ArchUnit番人の正式検証を待っていた。#3607 は全ArchUnit番人を専用JVMへ分離してOOMを対照する対策で、着地済みのため「候補」ではない |
| 48日 | [#1319](https://github.com/kenta-0420/mannschaft/pull/1319) | lint-staged 16→17 | **マージ済**（2026-10-03T21:23:23Z / 3fed9dea1b659cf277de0d417768e32283cc97e6） |
| 48日 | [#1318](https://github.com/kenta-0420/mannschaft/pull/1318) | vue-virtual-scroller | **マージ済**（2026-10-04T00:09:06Z / 64d10f6d7e2bf380031065952710ab0d4d25df15） |
| 48日 | [#1317](https://github.com/kenta-0420/mannschaft/pull/1317) | cheerio | **未処遇（OPEN）**。2026-10-30 時点 BEHIND/MERGEABLE。CI は Lighthouse と Smoke E2E が fail（他は pass、同一PRで2件のみ）。dependabot に `@dependabot rebase` を依頼済みだが「人手で編集されたため rebase 不可、`@dependabot recreate` なら再作成可」と返信（10-10）。殿の判断待ち |
| 48日 | [#1316](https://github.com/kenta-0420/mannschaft/pull/1316) | nuxt-security 1.4→2.6 | **クローズ済**（2026-10-27、dependabot 自動クローズ。コメント「Superseded by #4174.」）。後継 #4174（1.4.3→2.7.0）は OPEN。メジャーのため単独PRでCIを通すこと |
| 48日 | [#1083](https://github.com/kenta-0420/mannschaft/pull/1083) | @nuxtjs/i18n 9→10 | **マージ済**（2026-10-04T21:30:39Z / 16dc01a60605dbb0a9fc9d1cfa366b516a80f1bc） |
| 35日 | #1490 #1489 | **Spring Boot 3.5.13 → 4.1.0**（メジャー）/ AWS SDK BOM | **クローズ済（2件）**。#1490 は 2026-10-06 に dependabot が「Superseded by #3695.」で自動クローズ、#1489 は同日「Superseded by #3696.」で自動クローズ（#3696 も後に CLOSED、理由は「awssdk:bom は依存でなくなった」）。後継 #3695（Spring Boot 4.1.2）は OPEN だが CI 大半が fail のため見送り＝別戦役 |
| 14日 | #2728 | cloudflare 4.52→5.23（メジャー） | **クローズ済**（2026-09-01 JST）。[既存PRコメント](https://github.com/kenta-0420/mannschaft/pull/2728) は「Superseded by #3042.」。後続PR #3042はOPENで、依存更新の実施済みを意味しない |

依存の更新は放置するほど差が開き、まとめて上げるときに壊れる。
メジャーアップ（#1490・#2728・#1316・#1083）は単独 PR で CI を通してから畳むこと。

## 後継の依存更新PR（2026-10-30 調査）

調査のみ。rebase・マージ・クローズ・PRコメントは行っていない（殿の判断事項）。CI は `gh pr checks N --json name,bucket` の fail のみ列挙。

| PR | 中身 | 状態 / mergeable | CI fail | 処遇案 |
|---|---|---|---|---|
| #1317 | cheerio 1.0.0-rc.12 → 1.2.0（frontend, dev） | OPEN / BEHIND・MERGEABLE | Lighthouse、Smoke E2E | dependabot が rebase 不可（人手編集済）。`@dependabot recreate` で再作成が筋。cheerio の用途確認後に判断 |
| #3683 | actions/upload-artifact 4 → 7 | OPEN / BEHIND・MERGEABLE | Compile & Test、Test (shard 1)（他は pass） | shard 1 は依存と無関係の可能性大。rerun かmain追従後に再判定、通ればマージ候補 |
| #3685 | hashicorp/setup-terraform 3 → 4 | OPEN / BEHIND・MERGEABLE | なし（Terraform Plan は skipping） | マージ候補。ただし Plan 未実行のため main 追従後に infra 系ワークフローの挙動を確認 |
| #3687 | actions/setup-java 4 → 6 | OPEN / BEHIND・MERGEABLE | なし（全シャード pass） | マージ候補（main 追従後） |
| #3695 | Spring Boot 3.5.13 → 4.1.2（メジャー、#1490 の後継） | OPEN / BEHIND・MERGEABLE | Compile & Test、全6シャード、ArchUnit、Non-JST、Shard coverage、OpenAPI Drift | 見送り（別戦役）。クローズせず保留か、戦役化するまで放置 |
| #4174 | nuxt-security 1.4.3 → 2.7.0（#1316 の後継） | OPEN（CI 未調査） | 未調査 | メジャー。単独PRでCI確認 |
| #3689 | archunit-junit5 1.3.0 → 1.5.1 | CLOSED（2026-10-28、dependabot 自動クローズ「もはや依存でないため不要」）/ CONFLICTING | クローズ時 fail なし | 再起票の要否は下記「重要な所見」参照 |
| #3692 | pdfbox 3.0.3 → 3.0.8 | CLOSED（2026-10-28、同上）/ CONFLICTING | クローズ時 fail なし | 同上。pdfbox は `testImplementation` で 3.0.3 固定のまま残存 |

**重要な所見**: #3689・#3692・#3696（awssdk bom）の3件はいずれも dependabot が「もはや依存ではない」と判定して自動クローズした。ところが `backend/build-logic/src/main/kotlin/mannschaft.backend-conventions.gradle.kts` には pdfbox 3.0.3（131行目）や awssdk bom 2.29.45（85行目）が現存する。依存宣言が `build-logic`（precompiled script plugin）へ移ったため、`.github/dependabot.yml` の gradle `directory: "/backend"` では dependabot が検出できなくなった疑いが強い。この場合 Java 依存の更新PRは今後すべて自動では来ない。再起票より先に、検出経路（version catalog 化または dependabot の directory 追加）の整備が要る。これは仮説であり、dependabot の検出ログ（Insights → Dependency graph → Dependabot）での裏取りが残る。

## 初版に未掲載だった11件

以下は作成時刻が2026-08-26 10:41 JST以前で、最初の閉鎖またはマージがその後であり、再開イベントも無いことをGitHub timelineで確認した。元の31件へ加えると42件になる。取得できなかった当時のDIRTY/BEHINDや停滞理由は推測で補わない。

| PR | 内容 | 処遇 |
|---|---|---|
| [#2880](https://github.com/kenta-0420/mannschaft/pull/2880) | アンケートのADMIN+判定をMANAGE_SURVEYS委任へ統一 | **マージ済**（2026-09-17 JST） |
| [#2886](https://github.com/kenta-0420/mannschaft/pull/2886) | F14.3住民ライフイベントのアーカイブ Phase 1-A | **マージ済**（2026-09-25 JST） |
| [#2920](https://github.com/kenta-0420/mannschaft/pull/2920) | 村の存在照会ゲートの番人と残存箇所・ピン一覧の漏洩是正 | **マージ済**（2026-08-26 JST） |
| [#2945](https://github.com/kenta-0420/mannschaft/pull/2945) | roles/users/user_rolesのINSERT IGNOREを存在確認方式へ統一 | **マージ済**（2026-09-25 JST） |
| [#2949](https://github.com/kenta-0420/mannschaft/pull/2949) | bootstrapのhashicorp/aws 5.100.0→6.61.0 | **クローズ済**（2026-09-01 JST）。既存コメントは「Superseded by #3043.」 |
| [#2950](https://github.com/kenta-0420/mannschaft/pull/2950) | prodのhashicorp/aws 5.100.0→6.61.0 | **クローズ済**（2026-09-01 JST）。既存コメントは「Superseded by #3044.」 |
| [#2952](https://github.com/kenta-0420/mannschaft/pull/2952) | 統合カレンダービュー Wave 2-a のBE合成ビュー移行 | **マージ済**（2026-08-26 JST） |
| [#2954](https://github.com/kenta-0420/mannschaft/pull/2954) | 統合カレンダービュー Wave 2-b の月グリッド予定消失修正 | **マージ済**（2026-08-27 JST） |
| [#2956](https://github.com/kenta-0420/mannschaft/pull/2956) | マイカレンダーのチーム/組織予定の数値ID導線修正 | **マージ済**（2026-08-26 JST） |
| [#2957](https://github.com/kenta-0420/mannschaft/pull/2957) | 付随通知を業務TX外へ分離（CMP-056第1群ロットB） | **マージ済**（2026-08-26 JST） |
| [#2958](https://github.com/kenta-0420/mannschaft/pull/2958) | Gate基盤の止めてはならぬ域へALWAYSを付与 | **マージ済**（2026-08-26 JST） |

## 注意

**停止理由までは調べていない。** 本表が示すのは停滞日数・規模・マージ可否状態のみで、
意図的に寝かせているのか忘れられたのかは判別していない。処遇の判断には個別に中身を読むこと。
