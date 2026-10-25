# CMP-261025-1320 マイシフトの既存モバイルタップ領域

**未着手・修正未裁可**。CMP0647探索中に確認した既存mainの別件。今回差分への新blockerとはしない。機能実装/CSS変更0。

rootが82d/390×844で実画像とDOMを確認。チーム絞り込みnative button2個は高さ32px、日別詳細closeは32×32px。my/shift.vue:305/319のmin-h-[32px]、:516のh-8 w-8。既存44px mediaルールの.p-button対象にnative buttonが含まれない。[rootの元code/矩形照合](CMP-261021-0647/existing-mobile-observation.json)。my/shift.vueとmain.cssはorigin/main/headでblob同一（8fc655d65c691350602e7ace82f2c746be2297c9 / 6eac4b1d5ca965e6f4ef07e3c7c28d97f4804aa4）。

- [チップ実画像](CMP-261021-0647/images/mobile-existing-chips-390.png)：SHA256260515c443760ac60cbaa9a774a06df939396fe0c1004aa326f2faa6ce629f1f。
- [詳細close実画像](CMP-261021-0647/images/mobile-existing-close-390.png)：SHA256588d462c1c876ea72f43b4e8287f04e66da55670863c876dd6b07eac8f17084b。

完了条件案：360/390で該当native操作の44px領域確保、desktop既存密度/機能/scroll維持、回帰red→greenと実機画像/DOM確認。desktop35/40pxは既存明示md/密度設定に整合し不備扱いしない。採用/修正着手は別裁可。別件課題として登録。修正の採用・着手は未裁可。
