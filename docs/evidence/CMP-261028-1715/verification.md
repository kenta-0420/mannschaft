# CMP-261028-1715 検証記録

実artifact sourceは `488e4b9bf284bb6c996c2b87a09d37ad06a10bd6`。後続4697はspec-only bridge。コードPR4205はhead4697/merge2f524で採用済み。rootが最終CI17成功7条件skip・失敗/未完了0を確認。primary完了JSONとtask-listはこのdocs PRで確定する。

- [静的検分](review-static-safe.md)
- [正式実機8件](formal/formal-calendar-ui6-acceptance-safe.json) / [視覚補完](formal/formal-calendar-ui6-visual-supplement-safe.json)
- [6sample比較](performance/production-calendar-six-sample-comparison-safe.json) / [Nav・API診断](performance/visible-nav-prefetch-hook-probe3-safe.json)
- [赤緑の正式safe証拠](unit/red-green-evidence-safe.json)
- [採用元hash・変換記録](adoption-manifest-safe.json)

whole DOMはbaseline11.081/6.933s、calendar並列5.673/14.258s、Nav10.111/4.741sでばらつきが残り、全体解消は未証明。Grid HTTP取得はbaselineからlayers前に先行していた。Nav hookはネットワーク要求数ではなく、残存other1は未帰属。

正式実機はdesktop6/mobile2、rootがcorrected画像を実見してaccept。desktop補完は1280x1600 cropで性能条件とは異なる。自己CRUD1/1/1・共有write0・全logout200/全閉鎖。失敗した旧UI1–4/Nav2はhistoryへ保持し、driver起因と製品挙動を区別した。

独立3住民は各別actor/contextのfork-none探索。plain/returnは目的達成、mobileは個人scope直近週を確認した部分達成でTEAM/ORG未探索。3体の実施と全目的達成・coverage成功は別の判定。B0には既存26/26/55を不変保持して3/3宣言とmobileの未実証when-free観測1件だけを追加する。

作成時点の残るgate: この完了証跡PRの検分/CI/採用、本人runtime/成果物の後始末。overlay限定検証と既存標準validator失敗は分離して記録する。

## 正式redの採用範囲

- Calendar: `frontend/app/components/widgets/WidgetMyCalendar.spec.ts` のWidget準備中でも取得を開始する契約1件を実AssertionErrorで確認した。source b027の11件は3pass/8fail/0skipだが、missing initialize 6件とempty wrapper 1件をmeaningful redの根拠から除外する。`frontend/app/composables/useMyCalendarData.initialization.spec.ts` と合わせ、c787の公式greenは13pass/0fail/0skip/exit0。
- Nav: `frontend/app/components/shell/GlobalNavItem.spec.ts` のsource1569で可視先読みの陽性対照が成立した後、target hook長さ0期待で2件AssertionError。環境失敗や未実行をredへ数えない。4697のremoteCIでNav2/Widget5/initializer8のown15pass/0skipを確認。後続local targeted runは試験未実行のTERM/保持でありgreenへ変換しない。
- unit射影は公開specのテストラベルである`assertions.title`を全件除外し、元safe summary/receiptのSHA・spec basename・status・分類・総数を保持する。静的名に対応未証明のパラメータ名を推測復元しない。

## 住民画像と派生射影

rootは[一般住民の来月一覧](residents/plain/agenda-november.png)と[中断復帰後の表示](residents/return/06-return-list.png)を実視認した。画像内のmask対象文字そのものを確認したとは扱わない。mobileは原reportと07/09/10/11画像を候補として保持する。

safe JSON/JSONLは原本の派生projectionであり、採用画像だけをtracked pathへリンクした。未採用ローカル画像の参照値は明示マーカーへ変換し、実在しない画像pathを公開参照として残していない。未採用数・原本SHA・採用版SHAはadoption manifestで照合でき、原本自体は変更していない。[未採用alias一覧](local-images-not-adopted.md)は画像証拠ではない。

## 構成と検証限界

同じWindows browser/viewport/actor/SWblock/fresh contextを使い、logical FE3000→本人3011、public API8081→既存API8080へChromium限定port mappingした。Nitro internal APIは8080。既存APIは採用済みmain runtimeを全条件で共有し、新FE base b7とAPIのsource差を隠さない。各contextは資格情報前に実宛先を検証した。

[B0/portable限定検証](validation/portable-validation-safe.json)は正本関数・拒否境界・既存全deep equality・採用参照欠落0を確認。標準validatorは既存の「CMP正本行数と参照抽出数が一致しません」で失敗し、無関係修正は行わない。

## 最終CI・採用とB0反映

[最終対象15件](source/ci-unit-4697-targets-safe.json)はNav2/Widget5/initializer8、pass15/skip0。旧local targeted runはsnapshot段階でTERMし試験未実行、原receiptを保全して本人scratchだけ参照検査後に撤去した。[中断後始末](source/nav-green-linux2-interruption-cleanup-summary-safe.json)を成功した試験に変換しない。

[実装merge](source/implementation-merge-safe.json)はPR4205/head4697/merge2f524。通常mergeはuptodate制限で拒否された後、rootがmain追加docsのみ・全CIを再照合し正本条件のadmin squashで採用。使用中local branch削除の失敗をmerge失敗と混同しない。[main反映](source/main-adoption-safe.json)はroot所有guardで行われた。本資料作成担当はmain runtimeを操作していない。

B0は正規generatorを用い、既存26/26/55の全field・環境を保持して29/29/56。生成されたb0RunOverlayだけをdata.jsへ採用し、その他全field deep equality/HTML不変更を確認した。[限定一致検査](validation/final-overlay-safe.json)と[生成照合](validation/overlay-generated-equality-safe.json)を参照。新観測1件は既存taxonomy村予定calendar-viewへ紐付けた未実証when-freeで、execution/coverage合格ではない。最初の生成はsparseの既存参照spec34未展開で停止したため、参照存在metadataだけを追加展開して再実行し、spec/fixture内容は読取・採用していない。無関係inventory/GitHub同期とCMP既存不整合修正は行っていない。

## 検証CLIと本人runtimeの終了

local completion CLIは61fで過去全完了証拠・全参照を逐次git showするため長時間化し、root判断で所有読取Nodeだけを停止した。[中断safe](validation/completion-cli1-interrupted-safe.json)は未検証/exit-1でありgreenに数えない。最終docs headのGitHub Task-List完了ゲートが実PR/CI照合を含めて検証する。

rootがレビュー済みstrict helperで本人Nav NitroだけをSIGTERMし、3011解放/main API847218同一/他signal0を確認した。[停止safe](source/nav-fixed-owned-nitro1-stop-safe.json)。配布物削除は別gateで未実施、本陣3000/8080と外国3001を変更していない。

## 最新main追従と過去完了証拠の保持

初回docs gateは、進んだmainにあるCMP-260827-1152の完了証拠リンクを古いPR台帳が持たず失敗した。最新公開main8b8を通常mergeし、台帳はmain全行を保持してownCMP1715完了行だけを維持した。累積overlay衝突は最新main30/30/74の全fieldを保持してown3/3/1だけ追加し33/33/75。旧26/26/55→29/29/56証明は当時の履歴として残す。

正規generatorのoverlay同fieldだけを再反映し、source/生成data完全一致・他field不変・HTML不変更・原目的3件/部分達成維持を確認。既存completion-evidenceのselectEvidencePaths/validateCompletionChangesを最新main台帳対比で直接検証し、過去リンク保全・証拠blob一致と必要な参照を確認した。ガードは緩めず、長時間標準CLIは再起動していない。最終headのGitHubゲートで実PR/CI照合まで確認する。
