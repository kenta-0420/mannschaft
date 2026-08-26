package com.mannschaft.app.common.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DB に保存された生の R2 オブジェクトキー（例: {@code team/12/icon/x.png}）を、
 * 表示用の署名付き GET URL（絶対 URL）へ解決する共通部品。
 *
 * <p>画像は R2（ローカルでは MinIO）に保存され、DB には生キーのみが格納される。
 * これをそのままレスポンスへ返すと、フロントエンドが相対 URL として解釈し 404 になる。
 * そのため取得経路では本部品で都度 presign し、ブラウザが直接取得できる絶対 URL を返す。</p>
 *
 * <p>掲示板添付（{@code BulletinAttachmentService}）が取得時に都度 presign する方式を範とする。
 * presign は {@link StorageService#generateDownloadUrl} によるローカル署名計算のみで、
 * R2 への I/O は発生しない。</p>
 *
 * <p>本部品は表示 URL の解決という横断的関心事のみを担う。URL 解決はサービス/組立層で行い、
 * 解決済みの文字列を DTO へ渡すこと（DTO に Spring 依存を持ち込まない）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaUrlResolver {

    /** 表示用ダウンロード URL の有効期限（1 時間）。 */
    private static final Duration DOWNLOAD_TTL = Duration.ofSeconds(3600);

    private final StorageService storageService;

    /**
     * 生の R2 キーを署名付き表示 URL へ解決する。
     *
     * <p>キーが null または空白の場合は presign を呼ばず {@code null} を返す。
     * presign が失敗した場合も例外を伝播させず {@code null} に縮退する
     * （画像 1 枚の解決失敗で API 全体を 500 にしないため。症状を隠すのではなく、
     * 画像が出ないことを正直に null で表現する）。</p>
     *
     * @param r2Key 生の R2 オブジェクトキー（null/空白可）
     * @return 署名付き GET URL（絶対 URL）。解決不能時は {@code null}
     */
    public String resolve(String r2Key) {
        if (r2Key == null || r2Key.isBlank()) {
            return null;
        }
        try {
            return storageService.generateDownloadUrl(r2Key, DOWNLOAD_TTL);
        } catch (Exception e) {
            log.warn("画像URL解決に失敗（nullで縮退）: key={}", r2Key, e);
            return null;
        }
    }

    /**
     * 複数の生キーをまとめて解決する。同一キーはメモ化して presign を 1 回に抑える。
     * null/空白のキーは結果に含めない。一覧レスポンスで複数画像を解決する際に使用する。
     *
     * @param r2Keys 生の R2 オブジェクトキーのコレクション（null 要素可）
     * @return キー → 署名付き URL のマップ（解決できたもののみ）
     */
    public Map<String, String> resolveAll(Collection<String> r2Keys) {
        Map<String, String> resolved = new LinkedHashMap<>();
        if (r2Keys == null) {
            return resolved;
        }
        for (String key : r2Keys) {
            if (key == null || key.isBlank() || resolved.containsKey(key)) {
                continue;
            }
            String url = resolve(key);
            if (url != null) {
                resolved.put(key, url);
            }
        }
        return resolved;
    }
}
