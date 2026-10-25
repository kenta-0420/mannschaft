# CMP-261025-1320 マイシフトの既存モバイルタップ領域

**修正着手裁可済み・実装中**。2026-10-25の裁可に基づき、マイシフトのnative操作3定義をモバイル限定で44pxへ変更した。API境界fixture付き実Nuxt/CSSの番人はred 4件→green 7件。モックなし実機・ロール横断・探索は専有環境準備中で、まだ完了扱いにしない。

変更は`frontend/app/pages/my/shift.vue`のチームフィルタ2定義と日別詳細closeのみ。`max-md:min-h-11 max-md:min-w-11`で実buttonの幅・高さを確保し、closeへ既存翻訳`button.close`のaria-labelを付けた。desktopの既存クラス、アイコン、API、共通CSSは維持する。

番人は`frontend/tests/e2e/mobile/my-shift-touch-targets.spec.ts`。正式E2E CIのsmokeコマンドへ同specを追加した。360/390/767の実寸・中心軸上の端クリック、月次/週次のチーム絞り込み、詳細開閉、0件/1チーム、横パンなしを確認。768/1280は旧版で取得した矩形/computedと比較し、チップの矩形・computed、closeの幅高さ・min寸法・padding・font-sizeが一致した。closeのx/yはパネルenter transition中の採取なので同値判定に含めない。[集約証拠](CMP-261025-1320/fixture-summary.json)。

旧版はチップ高さ32px・短名チーム幅約33px、close32×32pxで寸法assertが失敗した。前提fixtureの公開フラグ未指定・通知WebSocket境界・丸角外クリックは是正し、これらを製品不具合や寸法redの根拠に数えない。正式red確定は4失敗/3成功・timeoutなし、修正後は7成功/0失敗。package.json・lockfile・main.css・専有依存46,845ファイルのfingerprintは不変。fixtureのAPI/WebSocket境界を置いたブラウザ番人であり、実BEの認可確認を代替しない。

## 元の既存main観測

CMP0647探索中に確認した既存mainの別件であり、CMP0647差分へのblockerではない。

rootが82d/390×844で実画像とDOMを確認。チーム絞り込みnative button2個は高さ32px、日別詳細closeは32×32px。my/shift.vue:305/319のmin-h-[32px]、:516のh-8 w-8。既存44px mediaルールの.p-button対象にnative buttonが含まれない。[rootの元code/矩形照合](CMP-261021-0647/existing-mobile-observation.json)。my/shift.vueとmain.cssはorigin/main/headでblob同一（8fc655d65c691350602e7ace82f2c746be2297c9 / 6eac4b1d5ca965e6f4ef07e3c7c28d97f4804aa4）。

- [チップ実画像](CMP-261021-0647/images/mobile-existing-chips-390.png)：SHA256260515c443760ac60cbaa9a774a06df939396fe0c1004aa326f2faa6ce629f1f。
- [詳細close実画像](CMP-261021-0647/images/mobile-existing-close-390.png)：SHA256588d462c1c876ea72f43b4e8287f04e66da55670863c876dd6b07eac8f17084b。

完了条件：360/390で該当native操作の44px領域確保、desktop既存密度/機能/scroll維持、回帰red→greenとモックなし実機画像/DOM・探索確認。desktop35/40pxは既存明示md/密度設定に整合し不備扱いしない。
