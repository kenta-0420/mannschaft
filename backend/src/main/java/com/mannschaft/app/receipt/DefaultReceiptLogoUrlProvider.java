package com.mannschaft.app.receipt;

import com.mannschaft.app.common.storage.MediaUrlResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 領収書ロゴの署名付き URL 生成の唯一の実装（F08.4 D-8）。
 *
 * <h2>プロファイル分岐を持たない理由</h2>
 * <p>当初は {@code @Profile("prod")} の R2 実装と {@code @Profile("!prod")} の
 * placeholder 実装（{@code https://cdn.example.com/<key>?signed=placeholder}）に
 * 二分されていたが、これは誤りだった。画像ストレージはローカルでも MinIO（S3 互換）で
 * 実際に動いており、prod 以外でダミー文字列を返す実装のせいで
 * ローカル・検証・E2E ではロゴが一切表示されなかった（F08.12 実機E2E 欠陥③）。</p>
 *
 * <p>正準実装である {@link MediaUrlResolver} はプロファイル分岐を一切持たず
 * {@code StorageService#generateDownloadUrl} で都度 presign する。本実装もそれに揃え、
 * 環境によらず実際に取得可能な署名付き URL を返す。キーが null/空なら null、
 * presign 失敗時も例外を伝播させず null へ縮退する扱いは {@link MediaUrlResolver} に委ねる
 * （ロゴ 1 枚の解決失敗で API 全体を 500 にしないため）。</p>
 */
@Component
@RequiredArgsConstructor
public class DefaultReceiptLogoUrlProvider implements ReceiptLogoUrlProvider {

    private final MediaUrlResolver mediaUrlResolver;

    @Override
    public String generateLogoUrl(String storageKey) {
        return mediaUrlResolver.resolve(storageKey);
    }
}
