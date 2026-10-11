# 最新main統合sourceのhosted production artifact製造候補

状態は **SOURCE_BOUND_UNRUN / root採用待ち**。固定checkout `3d307ff29336a424400fd01bc497d91fb2923c83`、Git tree `fa2317756875279bcc4f65d3f71e2b5baab8ca60`、parents `33a4489ed302309bff556545cce365f79d4f2c93` / `2280660b2fca00d71b00db06867a6399ab18dc99` からFE production outputとBE bootJarを作る。提案専用branch `feature/ranch-hosted-final-build-3d307ff293` と一意workflow名を使い、旧33a producer／成果物／実機証拠は保存する。branch作成・stage・commit・push・dispatchは未実施。

継承元は実run38076920462／eventaf8a72711a0cbb3a8c1cdf7230efbdb00f29f8a6の2-file producerで、workflow SHA62b573fe0492d092d17174e401a20a063ec5ef780dc4307b57c2d1f2602c512a。全job/run blockはbyte不変。Node22.23.2・Java21・max2jobs、FE実effective memory12GiB以上を2回確認、heap8192MiB＋reserve4GiB、UID1000・offline network-none build、distinct空npmconfigs、hydrate/offlineの--include=dev・set-euo・actual exit記録、FE3103→BE18082/proxy/apiBase空/development visuals、exact16linksと固定output-validatorを保持する。既存normal CI／製品は変更しない。

package.json／package-lock.jsonのcanonical Git blobは旧33aと完全同一。Windows checkoutのCRLF bytesとは区別する。新actual output/JAR/archive/proof SHAとrun/eventは未確定で、旧33aのSHAを新HEADの結果として流用しない。元HOSTED proof byte保持＋fresh owned placementの再検証とlocal dependency/prepared/serveは別途必要である。

これは新sourceのbuild／prepared／serve／E2E／fullCI／release成功を意味しない。新Git identity・provenanceを実artifact受領後に厳密にbindする。旧33a runtimeはその元identityの証拠としてのみ保全する。
