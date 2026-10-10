# Ranch 最終sourceのhosted production artifact製造

状態は **SOURCE_BOUND_UNRUN**。専用branch `feature/ranch-hosted-final-build` の独立workflowが、固定source `33a4489ed302309bff556545cce365f79d4f2c93`（tree `fa8805368736f6f97909200183e7ab1c5ced97df`、main `120257` 取り込み済み）からFE production outputとBE bootJarを製造する。既存normal CI、製品コード、全テストのgateは変更しない。actual hosted build、成果物、配置、実機成功はまだ得られていない。

FEはNode22.23.2・UID1000、準備前とbuild直前の実MemAvailable/cgroup残余からeffective12GiB以上を要求し、不足時は拒否する。heap8192MiB・4GiB reserveを維持。公開依存準備後、専有cacheでoffline install/buildをnetwork-none container内で行う。apiBase空、同一origin3103、内部API/proxy127.0.0.1:18082、development visuals trueを固定する。image tagをimmutable pinとは称さず、actual image IDをproofに記録する。

既存output-validatorのexact bytesと16本の内部relative symlink検査を使い、output tarはsymlinkを保持する。BEはJava21でcompileJava+bootJarのみ。両jobはactual shell/container build exit、source tree、lock/output/JAR bytes/SHAを有限metadataへ残し、私有build logs・cacheはuploadしない。元hosted proofをlocal buildやOWNED_GROUPへrelabelしない。

成果物をWSLへ輸送する場合もoriginal hosted proofはbyte不変で保持し、別のfresh owned配置・再検証proofが必要。serve06の既存local path/producer schemaへそのまま渡すことはできない。source archiveとlocal source byte tree、FE source before/after、16links/output tree、lock/JAR digestを実再計算し、不一致を拒否する。配置adapterは未製造。`.output`だけではinstalledLock、Playwright package/browser cacheは揃わず、承認済みmatching依存の配置・実pins確認も必要。

この製造はprepared/serve/E2E/fullCI/正式releaseの証拠ではない。fresh DB/Valkey、FE3103/BE18082、短専有TMP、CSP/proxy/BE停止negative controlの実検証は別途必要。rootは元proposalの全7 BashブロックとNode22 launcher構文の実exit0を確認済みだが、宿主memory budget・Docker/cache・実build結果を証明するものではない。

初回run `38075847925` はBE製造成功、FEは依存準備で失敗しcompileへ到達していない。rootの既存Node22/npm10.9.8によるversion-only確認では、同じ空ファイルをUSERCONFIG/GLOBALCONFIGへ指定した場合は実exit1（double-loading-config）、異なる空ファイルでは実exit0/version10.9.8。hydrate/offline双方を別々の空設定ファイルに修正した。hydrate内shellはset-euoでnpm失敗を伝搬し、外側containerのactual exitをhydrate-exit.txtへ記録して明示uploadした後、0を要求する。元run/proofは保持し、再実行・FE build成功・配置成功はまだ未確認。

offline側もshell冒頭をset-euoとし、設定作成・cache copy・Node22 version確認の失敗を伝搬する。npmci・lock・launcherのchainだけは既存set+eとactual exit記録を維持する。
