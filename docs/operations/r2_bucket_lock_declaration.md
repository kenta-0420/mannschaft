# R2 Bucket Lock宣言管理

Cloudflare R2のBucket Lockを宣言ファイルで管理する。対象は`receipts/`プレフィックスだけ、保持期間は7年（220,924,800秒）である。秘密情報は宣言や証跡に保存しない。

既定モードはdry-runで、外部通信を行わない。inspectはGETで現状を照合し、applyは明示的なゲートを通過した場合だけPUT後にGET照合する。

安全策:

- accountとbucketの両方をallowlistに登録しなければ実行しない。
- bucket名にはsandbox、dev、testのいずれかを含める。
- prod、production、liveを含む識別子と、実本番バケット`mannschaft-storage`は機械的に拒否する。
- `R2_LOCK_ALLOW_APPLY=true`がないapplyは拒否する。
- HTTP失敗、JSON以外の応答、APIエラー、宣言との差分は非0終了する。
- API要求には10秒のタイムアウトを設定する。

実サンドボックスでのE2Eは、資格情報と対象の確認後に別途実施する。本番環境への通信は行わない。

仕様参照: https://developers.cloudflare.com/r2/buckets/bucket-locks/
