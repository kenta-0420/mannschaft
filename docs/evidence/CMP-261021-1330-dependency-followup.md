# CMP-261021-1330: 監査失敗への依存修正版追従

PR4194 の固定元 8c03cc20c480b1f55392151927d64e1f96299c77 は、Frontend CI run40809793266/job123237136634 の npm audit で `許可されない脆弱性: high @intlify/core` により失敗した。npm ci と監査回帰試験は成功していた。保存npm audit JSONがないため、当時の全findingとの完全一致は未確認。

既存PR4218の固定head d5ea3b45b8466698a5c62aebc12aa3f3198e661e から、lockだけを変更する既存commit e9ac540474b5f8c442dde052572feb3452cce6db を cherry-pick -x で再利用した。後続の他owner台帳登録commitは含めない。独自upgrade/overrideは追加していない。

`frontend/package-lock.json` のGit blob実bytes SHA256は、旧 `7384f7f465f56cae36394cf4e65b108fab668ff5401ea766e2918fd3308eaa55`、新 `6b77b2eda04e5e021d2035251269d7271c40057dd4c37e2ee17d01673aa2296d`。旧lockは8c03と再利用元の親で完全一致、新lockは固定PR4218と完全一致する。core/core-base/shared/message-compiler/devtools-types/vue-i18n は11.4.16へ、unplugin/bundle-utilsは11.2.8、extensionsは9.0.0へ追従し、extensions配下の旧10.0.8一式を除去した。

[GHSA-83rm-6c8r-g728](https://github.com/advisories/GHSA-83rm-6c8r-g728) はhigh、[GHSA-8rc2-428q-7ww3](https://github.com/advisories/GHSA-8rc2-428q-7ww3) はlow。11系列のfirst patched versionは11.4.14で、[公式11.4.14 release](https://github.com/intlify/vue-i18n/releases/tag/v11.4.14)に修正がある。既存commitの11.4.16をそのまま採用した。

package.json、監査script、承認済み例外の対象・期限・severity、監査閾値は不変。Node条件はcore群>=22、bundle/unplugin>=22.13、extensions>=22で、既存Node22.23.2を満たす。peer条件は既存Vue3.5.43・Vite7系列・vue-i18n11.4.16を受理する。対象ページおよびja/en/zh/ko/es/deの6localeソースは8c03から変更していないが、依存更新後の画面動作が検証済みという意味ではない。

固定PR4218 head d5ea3 のCIではnpm ci・監査回帰・npm audit・lint・typecheck成功を確認した（Frontend CI run40817238248/job123261183040）。これは本PRの新headのCI代用にはしない。本headでのCI、対象ページの必要実機確認、独立住民探索は未完了。出荷・実機完了の根拠とは扱わず、各結果を新しいheadへ結合してから判断する。