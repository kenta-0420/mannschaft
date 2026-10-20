# 大会エントリー・組織公開範囲・人数ヘッダーの探索証跡

対象はCMP-260929-0654 / PR #3759、CMP-261010-1129 / PR #3782、CMP-261010-1131 / PR #3881。原住民探索の観測から統括がcanonical assertion redを取得し、製品修正後の新1064 productionでcanonical greenと同条件画像を照合したことで、この3件の探索gateを完了した。新しい同目的3住民runを重複実行していない。

## 原住民の実行識別

| persona / journey | 元runId | actor | 時刻UTC | 目的と結果 |
|---|---|---|---|---|
| ORPHANS-MOBILE-20261015 / ORPHANS-ENTRY-20261015 | mobile-2026-10-15T10-14-25-196Z | 23 | 2026-10-15T10:14:25.196Z〜2026-10-15T10:19:01.312Z | スマホでチームの大会エントリーを整え保存結果を判断する。達成 |
| ORPHANS-ADHD-20261015 / ORPHANS-ORG-CREATE-20261015 | org-interruption-1792059274293 | 24 | 2026-10-15T10:14:34.292Z〜2026-10-15T10:20:55.812Z | 限定公開の組織を作成し中断後に結果と公開範囲を確認。達成 |
| ORPHANS-MULTILINGUAL-20261015 / ORPHANS-HEADERS-20261015 | multilingual-1792059350281 | 90245 | 2026-10-15T10:15:50.281Z〜2026-10-15T10:24:23.992Z | 公開組織とチームの規模・閲覧できる情報を読み取り参加を検討する。達成 |

元sourceは `cc96ec081cc3a812f49d14f489e92242d19f4b06`。各actorは独立contextで目的だけを与えられた。多言語利用者は元言語jaへUI復帰し保存200を確認した。PIDは指定原本metadataに記録がないため推定しない。

原本は実保全済みarchiveのentry配下（`.claude/evidence/orphans-20261015-final-20261015T114005Z-ff577aa6/entry/`）の `resident-mobile-entry-20261015/{manifest,report}.json`、`resident-org-interruption-20261015/{manifest,report}.json`、`resident-multilingual-headers-20261015/observations.json` と各report.md。元の観測9件は全て `trusted:false / observed-unverified` のままで、包括的なバグ認定やcoverage合格へ変換していない。

| 原観測 | 観測要旨 | この3案件の追加検証との関係 |
|---|---|---|
| ORPHANS-MOBILE-20261015 / OBS-MOBILE-1 | 大会チーム表の数と管理ボタンが複数行へ折り返す | scope外の観測として未検証を維持 |
| ORPHANS-MOBILE-20261015 / OBS-MOBILE-2 | 保存済み表の名前・備考が細い列で縦へ折り返す | scope外の観測として未検証を維持 |
| ORPHANS-MOBILE-20261015 / OBS-MOBILE-3 | 360pxのPDF・閉じるフッターが2段になる | scope外の観測として未検証を維持 |
| ORPHANS-ADHD-20261015 / OBS-HEADER | 768px組織ヘッダーの見出しと管理者・人数が縦に縮む | 関連する修正対象だけ統括のcanonical red→greenを確認。元statusは維持 |
| ORPHANS-ADHD-20261015 / OBS-VISIBILITY-LABEL | 限定公開が日本語基本情報でPRIVATEと表示される | 関連する修正対象だけ統括のcanonical red→greenを確認。元statusは維持 |
| ORPHANS-ADHD-20261015 / OBS-TARGET | 組織作成フォームの送信・閉じるが44px未満 | 関連する修正対象だけ統括のcanonical red→greenを確認。元statusは維持 |
| ORPHANS-MULTILINGUAL-20261015 / MLH-CAND-1 | 公開組織・チームの主要CTAが35px高 | 関連する修正対象だけ統括のcanonical red→greenを確認。元statusは維持 |
| ORPHANS-MULTILINGUAL-20261015 / MLH-CAND-2 | 多言語の基本情報・メンバー表に日本語が混在する | scope外の観測として未検証を維持 |
| ORPHANS-MULTILINGUAL-20261015 / MLH-CAND-3 | 人数1の組織で公開メンバー表が空と表示される | scope外の観測として未検証を維持 |

## 修正後の対象検証

- entry:保存済み選択・mobile重なり・主要操作44px・input16px・notes200保持の実測redから修正し、新1064/新BEで10 PASS・既存fixme 1 SKIP、同条件の添付7件/unique6画像を統括が照合した。詳細は[実機証跡](CMP-260929-0654-real.md)と[entry画像manifest](orphans-entry-image-manifest-20261015.md)。元mobile住民の表折返し等の観測は未検証のまま。
- org:操作領域40/42px・label20pxとraw PRIVATEのcanonical redを統括が実測。修正後1064でfull real 3 PASSと追加PRIVATE 1 PASS、同条件画像2件の実見で解消を確認した。
- header:768px人数6行とmobileBack32pxのcanonical redを統括が実測。修正後1064で11 PASS、同条件画像14件の実見で解消を確認した。
- 原本run/sourceは固定し、修正後結果はsource `1064ff0e7dca4513082ca83ddb7e877c5d2ad967` のpost-fix検証として分離する。PR headとの対象同値は統括のruntime/source証跡で確認済み。各案件のreal.mdに実runのproof/logと対応範囲を記載した。

## 監視範囲・保全

mobile住民は観測範囲内のpageerror・HTTP400以上を0件と報告したが、validationエラー・PDF・テンプレート適用等は探索していない。org住民はAPI/console全件監視未実施、他ユーザー可視性は担当外。多言語住民は加入/フォローやvalidation/modalを探索していない。別途full realによる権限境界検証と混同しない。

[画像manifest](orphans-org-header-image-manifest-20261015.md)は所在・SHAのみ。原本map org3件のうち統括指定の安定photo01/03の2件、header32件のうち指定14件だけを採用した。番号と実map filenameを機械照合済み。原本map全件を視認したとは申告しない。PNG/PII/raw system-out/key/envは保存文書へcopyしていない。case3のraw Linux log/XMLは公開・archive対象外。

自作org603のDELETE204→正規GET404、今回の27有効対象の正規GET404、探索専用5fixtureのDELETE204→正規GET404は統括確認済み。org599は許可待ちの別保全対象で削除済みに数えない。scope外の表折返し、日本語混在、人数と公開一覧empty等は未検証のまま残る。
