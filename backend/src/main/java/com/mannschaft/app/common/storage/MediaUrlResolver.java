package com.mannschaft.app.common.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 既存の非 ACL 管理メディアの表示 URL を解決する互換サービス。
 *
 * <p>キーが null・空、または署名発行に失敗した場合は null を返し、一覧・プロフィール表示を
 * 個別のストレージ障害で 500 にしない。CMP-057 の CONTENT_BOUND オブジェクトには使用禁止であり、
 * その URL は {@code StorageAccessService} が ACL、スコープ、親コンテンツ参照、attachment binding を
 * 照合してから発行する。この resolver を保護コンテンツに使うと照合を迂回するため、互換用途として
 * 非推奨にしている。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaUrlResolver {

    private static final Duration DOWNLOAD_TTL = Duration.ofSeconds(3600);

    private final StorageService storageService;

    /**
     * 単一の既存メディアキーを署名 URL へ解決する。
     *
     * @param r2Key R2 object key
     * @return 署名 URL。キー不正または署名失敗時は null
     */
    @Deprecated(since = "CMP-057", forRemoval = false)
    public String resolve(String r2Key) {
        if (r2Key == null || r2Key.isBlank()) {
            return null;
        }
        try {
            return storageService.generateDownloadUrl(r2Key, DOWNLOAD_TTL);
        } catch (Exception e) {
            log.warn("画像URL解決に失敗したためnullへ縮退します: key={}", r2Key, e);
            return null;
        }
    }

    /**
     * 複数の既存メディアキーを重複なく解決する。
     *
     * @param r2Keys R2 object key collection
     * @return key と署名 URL の対応。解決不能なキーは含めない
     */
    @Deprecated(since = "CMP-057", forRemoval = false)
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
