# CMP-260910-1555 独立住民探索記録

rootはfresh3人・別context/別loginによる実UI探索、逐次safe保存・実閉鎖、root裏取りと[観測PR #4234](https://github.com/kenta-0420/mannschaft/pull/4234)のmain採用をもってexploratory passedと判定した。全目的達成を意味しない。元runは`CMP-260910-1555-revalidation-20261030-11`、sourceは`588407f98b2b7aecd99897c26bdd3b6129768a82`。操作手順を教示せず、目的・担当合成対象・境界・期限だけを提示した。ハーネス作者を住民へ転用しなかった。

| 住民 | 目的結果 | 実観測 | operate（DOM読取含む） |
|---|---|---|---|
| A ADHD×中断 | achieved | 下部設定で迷い/locator timeout。1250保存→1350未保存取消→離脱復帰1250履歴→同日1350訂正保存→再復帰一覧/入力/履歴1350。成功と摩擦を分離 | 47=38成功/9失敗 |
| B 一般MEMBER×境界 | unmet | 自チーム一覧/マイシフト/シフト管理/マイページ/メンバー情報/データ設定を自律探索したが、自分時給表示/管理入力を発見できず。登録試行0・他人変更0。全経路不在や権限制約の正常性は未判定 | 30=26成功/4失敗 |
| C スマホ片手 | achieved | 390×844/360×800、isMobile=true/hasTouch=trueでtap/scroll。担当Cだけ1300円/2026-11-01保存、両幅再開履歴確認。日32×32/月50×32/年52×32、小領域は未実証観測。横タブ初回timeout→横scrollで進行。将来日後の一覧/入力空は仕様の可能性を保持 | 39=35成功/4失敗 |

各context1created/1closed、close+EOF/session exit0・rootlive0。A選別2PNGの最終1350画像はroot実視認、B選別1PNGは本人保存のみ/rootが表示部分を実視認、C9PNGは本人/rootとも全9実視認。画像実体は公開せず[選別観測](CMP-260910-1555/resident-observations-20261030.json)のprivate artifactID/SHAを参照する。B画像1枚から全導線不在を結論しない。Cの物理スマホ/Safariや125%実ブラウザzoomは未試験。

既存36persona/36journey/79insightの全値/元run/headを保持し、39/39/82へ3件追加した。`actual-resident-artifacts / trusted=false / observed-unverified`を維持し、coverageや確定バグへ昇格しない。ローカルボードは初回82→reload82・deepEqual・新3cardと旧79件のimport投影保持を確認した。既存importerのstored runIdは`october-wave2-login-resident-overlay-20261026`でoriginalRunIdPrecisionPassed=false、元runはdetail/選別証拠に保持する。通常board validatorのbaseline不一致と残global未到達は別制限。

root別受入artifact `run11-root-parent-adjudication-safe.json`（SHA256 `c8774a9b86a82cb620ca9578aaa9ac4b9c2bed9deff9064f08d4c4adfed80291`）とroot実視認artifact `run11-root-resident-visual-review-safe.json`に基づく。過去2116や旧失敗runを成功に付け替えず、現runの未裁定字段も書換えない。

## 保持する制限

- CMP-261021-0651の汎用拒否UX、CMP-260907-1038の小さな日付タップ領域、optional副管理者メニュー未達を保持する。SYSTEM_ADMIN/DEPUTY予算UIの観測を一般成功へ拡張しない。
- Bの自分時給導線発見は目的未達。探索passedは独立3人の実探索・機械閉鎖・root裏取り・観測登録を意味し、全目的達成や製品無欠陥ではない。
- 固定source588の現organization方式だけを受入対象とする。team-owned v2は設計のみ/未実装で準拠を主張しない。
- 閾値N±1の直前/直後境界は未確認。既存120名のテストは入力130%であり、実run11のexact120%と区別する。CI集計成功を個別test XMLの実行証明へ置き換えない。
- 小規模合成fixtureの応答/通知時間のみ。負荷・SLA・物理端末/Safariは未試験、125%条件はviewport近似。
- 通常board validatorはbaseline同一のCMP/GitHub参照集合不一致で停止し、残global条件未到達。ローカルUIの82→82取込・再読込非重複と分離する。
- 既存board importerのstored runIdはoctober-wave2-login-resident-overlay-20261026。originalRunIdPrecisionPassed=falseで、元runはdetail/選別証拠に保持する。
- 検証後のworktree切替/sparse操作後、11候補package原本が喪失した。別元3UI scriptは残存・root保全済みだが、v12完全原本による再現は未確認。実行時SHA・外部safe・選別PNGは保持。消失時点/Windows stage同時消失は未確定で、事故を正常cleanupや削除許可済としない。
- 旧run失敗・起動前timeout・fixture/UI/harness失敗は履歴として保持し、run11成功で上書きしない。原ownerの既定not-run/campaignAcPassed=false等は書換えず、root別受入記録で原3ACを裁定した。

- 実機source588と正式文書base1d9はBE/FE全tree同一ではない。rootは親shift/shiftbudget関連経路の差分なしと変わった二次依存の範囲を確認したが、最新main全体を実機済みとは主張しない。same-tree CI橋渡しは588と4214/4221に限定する。
