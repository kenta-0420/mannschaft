# 2026-10-26 既存完了証拠の重複同期

この記録は、すでに完了している画面修正と同じ完了条件が台帳に重複登録されていたため、既存の完了証拠を参照して台帳を同期したものです。新しい実装修正ではありません。今回、新規テスト、実機操作、住民探索は再実行していません。

## CMP-261004-1945

既存完了 CMP-261018-1329 / [PR #3970](https://github.com/kenta-0420/mannschaft/pull/3970) のAC-1（公開組織・チーム向け案内の6言語表示）とAC-2（personal/PUBLIC以外の非表示、dismiss後の同一scope抑制とscope分離）が、この行の完了条件に対応します。殿は2026-10-26に現行ScopeDashboardの組織キー分岐を読み、組織ページでは組織の文言を返すことを確認しました。

実装PR headは `848c88e36d581fe30e3d271a90a188da8244ce32`、通常mergeは2026-10-18T08:51:37Zです。既存の [完了証拠](CMP-261018-1329.json) と [実装・実機・回帰記録](CMP-261018-1329.md) を参照します。同headのCIは11 workflow runs成功、15 checks成功・7 skipped、630 files / 6,223 tests成功を元記録に残しています。今回、GitHubでrun 11件すべてのhead一致とsuccessを照合しました。

既存記録には、実機runtimeとPR headが全tree同一ではなく、12 filesの同一性と限定したlocale差分を根拠とすること、SSR/CSPは未検証であること、探索は記録済みresident範囲に限ることが記載されています。今回それらのrunや操作を再現したものではありません。

## CMP-261004-1946

既存完了 CMP-261010-1131 / [PR #3881](https://github.com/kenta-0420/mannschaft/pull/3881) のAC-5（en/es/deの360/390pxで両Headerの主要操作領域を44px以上）が、この行の完了条件に対応します。殿は2026-10-26に現行の組織・チーム両Headerの44pxルールを読み合わせました。

実装PR headは `79c25a01675e1d72586b93cedb09f138043b0945`、通常mergeは2026-10-15T12:23:20Zです。既存の [完了証拠](CMP-261010-1131.json)、[検分記録](CMP-261010-1131-review.md)、[実機記録](CMP-261010-1131-real.md)、[探索記録](orphans-org-header-exploratory-20261015.md) を参照します。実機記録にはmobileBack 32pxのredから44pxのgreenへの修正が残っており、今回GitHub上でCI run 38731338367がこのheadのsuccessであることを照合しました。既存記録にあるruntime対応の限界や未検証範囲はそのまま引き継ぎ、再実行したとは主張しません。

## 棚卸同期

- 正本: `origin/main` のtask-list commit `4fc71c9001bf4096cfbe339708c189597c80e9a4`、feature inventory commit `d61097a49227386d8b4044a2a6ced72fade5b50a`。
- GitHub同期: 2026-10-26T07:31:33Z、664 references。標準ボード生成時刻は2026-10-26T07:33:07Z。
- 生成集計: CMP/campaign records 596件、features 44件、capabilities 95件、CORE 25件、非CORE 19件、blockers 23件。
- 既存B0 overlay `october-first-wave-20261026` の3 personas、3 journeys、OBS-A1/A2/A3/B1/B2の5 insightsを完全保持しました。`trusted=false` / `observed-unverified` と各ID・persona・journey・run・source・evidenceを変更していません。
- 標準ボードvalidatorは成功しました。依頼範囲にデスクトップHTML反映は含まれないため更新していません。
