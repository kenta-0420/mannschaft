# CMP-261022-0338 住民探索記録

3名の住民が実画面を触った探索はrun october-first-wave-20261026、source head 03be10c6d87ceeec29d4b09f0bac4db0d391c514 の記録。ADMIN4、ORG_ADMIN7、MEMBER8の別アカウントを使い、360/390pxの画像とDOMを確認した。個々の観測・制約・代表画像は [住民A](admin.md)、[住民B](organization-admin.md)、[住民C](member.md)。

この探索は最新T実機 e202 より前のsource上である。対象component/関連guard16 blobがmain追従前後で不変であることは [main-follow evidence](../e202/main-follow.json) で照合したが、住民探索をe202上の再実行とは主張しない。最初のbrowser driverはstdin閉鎖で操作不能となり、A/Bは逐次に再loginした。別contextでの同時利用はしていない。

観測された5件はすべて observed-unverified の候補として記録し、現ACへ追加・blocker化していない。Aでは保存後のcomment表示未確認、担当者操作の縦wrap/カード端越え、幅40pxのtab送り。Bでは組織名表示候補と権限理由への迷い。Cは権限toast、通常UIへの復帰、CSP object-src violation後の自動report POST 204とUI継続を観測した。詳細は [resident overlay](../resident-overlay.json) と各要約にある。raw観測JSONは個人/実行情報を含むため複製せず、SHAと元pathを [artifact index](../selected-artifacts.json) に記録した。

これは探索者の手操作による観察であり、B0 resident runner/coverageの実行結果ではない。B0 runnerは未実行で、coverage達成やtrusted validationを主張しない。