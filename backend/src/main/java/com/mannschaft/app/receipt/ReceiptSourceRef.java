package com.mannschaft.app.receipt;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@code receipts.source_ref} の値オブジェクト（F08.12 §3.1）。
 *
 * <p>収入 3 系統の主キー型が揃っていない（{@code ad_invoices} / {@code notification_credit_purchases}
 * は {@code BIGINT UNSIGNED}、{@code billing_invoices} は {@code BINARY(16)} の UUIDv7）ため、
 * 列は {@code VARCHAR(64)} で切り、<b>文字列表現の規約をこのクラス 1 箇所に閉じ込める</b>。</p>
 *
 * <h2>格納形式（ぶれると照合が壊れるため厳密に定める）</h2>
 * <ul>
 *   <li>{@code BIGINT} 系 … 10 進数の文字列。<b>ゼロ埋めしない</b>（{@code "12345"}）</li>
 *   <li>{@code UUIDv7} 系 … {@link UUID#toString()} の<b>小文字ハイフン付き 36 文字</b></li>
 * </ul>
 *
 * <p>呼び出し側に {@code String.valueOf(id)} を書かせないこと。書けた瞬間に、ゼロ埋め・
 * 大文字 UUID といった揺れが混入し、{@code (source_type, source_ref)} での照合が
 * 静かに空振りするようになる。</p>
 */
public final class ReceiptSourceRef {

    /** 10 進数（ゼロ埋めなし）。先頭 0 は単独の "0" のみ許す。 */
    private static final Pattern NUMERIC = Pattern.compile("^(0|[1-9]\d{0,19})$");

    /** 小文字ハイフン付き UUID（36 文字）。 */
    private static final Pattern LOWER_UUID =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    /** {@code source_ref} 列の長さ上限。 */
    public static final int MAX_LENGTH = 64;

    private final String value;

    private ReceiptSourceRef(String value) {
        this.value = value;
    }

    /** {@code BIGINT} 系の元データ ID から生成する。 */
    public static ReceiptSourceRef of(Long id) {
        Objects.requireNonNull(id, "source ref id must not be null");
        if (id < 0L) {
            throw new IllegalArgumentException("source ref id must not be negative: " + id);
        }
        return new ReceiptSourceRef(Long.toString(id));
    }

    /** UUIDv7 系の元データ ID から生成する。 */
    public static ReceiptSourceRef of(UUID id) {
        Objects.requireNonNull(id, "source ref id must not be null");
        return new ReceiptSourceRef(id.toString().toLowerCase(Locale.ROOT));
    }

    /**
     * DB から読み出した文字列を復元する。格納形式に合致しない値は受け付けない。
     *
     * <p>不正値を黙って通すと、取引先検索（§4.1）が静かに空振りする。ここで落とすのが
     * 唯一の観測点であるため、握りつぶさない。</p>
     */
    public static ReceiptSourceRef parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("source_ref must not be blank");
        }
        String trimmed = raw.trim();
        if (!isValid(trimmed)) {
            throw new IllegalArgumentException("source_ref does not follow the storage format: " + trimmed);
        }
        return new ReceiptSourceRef(trimmed);
    }

    /** 格納形式に合致するかを判定する（番人テストと共有する唯一の判定）。 */
    public static boolean isValid(String raw) {
        if (raw == null || raw.length() > MAX_LENGTH) {
            return false;
        }
        return NUMERIC.matcher(raw).matches() || LOWER_UUID.matcher(raw).matches();
    }

    /** DB へ格納する文字列表現。 */
    public String value() {
        return value;
    }

    /** {@code BIGINT} 系として元データ ID を復元する。 */
    public Long asLong() {
        if (!NUMERIC.matcher(value).matches()) {
            throw new IllegalStateException("source_ref is not a numeric id: " + value);
        }
        return Long.valueOf(value);
    }

    /** UUID 系として元データ ID を復元する。 */
    public UUID asUuid() {
        if (!LOWER_UUID.matcher(value).matches()) {
            throw new IllegalStateException("source_ref is not a uuid: " + value);
        }
        return UUID.fromString(value);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ReceiptSourceRef other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
