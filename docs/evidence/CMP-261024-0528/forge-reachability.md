# node-forge署名検証の到達性調査

対象: feature/cmp-261024-0528-dashboard-calendar-dialog / ff92de745f9f75e48e532e03451e789b500452ec。
対象advisory: GHSA-86w9-cpqp-85rv (CVE-2026-85393)。RSA PKCS#1 v1.5の署名検証。

## 結論
現行ソースと固定依存の静的調査では、問題の署名検証処理を呼ぶ経路は見つからない。ライブラリの存在と脆弱処理の利用は分ける。独自listhenへの置換や証明書機能削除の必要性を本調査結果からは認めない。依存の脆弱性自体が修正されたわけではなく、監査ゲートの期限切れ失敗は別途残る。例外延長・閾値変更はしていない。

## 証拠
- lockのnode-forge直接消費者はnode_modules/listhenのみ。固定版はlisthen1.10.0/node-forge1.4.0。
- registryの該当tarを取得し、両者のSHA512 integrityがlockと一致(integrity.json)。最新listhen1.10.1も参考取得したが、判断は固定1.10.0に基づく。
- listhen-1.10.0/package/dist/shared/listhen.BgWF2Mzc.mjs:304 resolveCertificate。鍵/証明書指定読込み、PFX読込み、生成の分岐。
- 同:458以降signCertificateでcert.signを呼ぶ。verify/verifyCertificateChain呼出なし（ESM/CJS配布物全体検索）。
- 同:607〜610 https指定時だけresolveCertificateへ入る。frontend/nuxt.config.ts:114のdevServerにhttps指定なし。
- forge/package/lib/x509.js:1128 cert.signはkey.signを呼ぶ。cert.verify(:1157)とは別。署名に検証処理を暗黙に伴わない。
- forge/package/lib/rsa.js:1142 key.verifyが問題の検証側。listhenから呼ばれていない。
- PFX読込みのforge/package/lib/pkcs12.js:467以降はHMAC照合。RSA署名検証と別で、当該verify呼出なし。
- frontend/app・frontend/serverにnode-forge、forge、pkcs1、publicKeyFromPem、verifyCertificateChain、createVerify、.verify(の呼出検索0件。frontend全ts/js/mjs/json(lock/監査script除外)も無関係なテストコメントのみ。
- 既存docs/security/04_dependency_and_supply_chain.md:115にも同様の過去調査記録あり。

## 本番構成・限界
- frontend/Dockerfile:45 nuxt build、:59で.outputのみruntimeへCOPY、:75 node server/index.mjs。
- 現在の.outputはWTにも以前のWSL一時先にも存在しない。本番成果物からnode-forgeが除外されることは未実測。
- 本番稼働プロセス・全環境の注入変数・ランタイムトレースは未調査。将来の依存/設定変更に結論を無条件適用しない。
- アプリにJWTやwebhook等の署名検証が一切ないという主張ではない。対象はnode-forgeの上記RSA検証。
- 製品コード/依存/監査例外/PR状態に変更なし。旧一時環境消失のためnpm ciやbuildは新たに実行していない。
