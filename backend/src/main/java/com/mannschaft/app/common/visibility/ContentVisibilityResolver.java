package com.mannschaft.app.common.visibility;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * 個別 {@link ReferenceType reference_type} 用の可視性判定 Strategy。
 *
 * <p>1 reference_type につき 1 つの実装クラスを置き、{@link ContentVisibilityChecker}
 * がコンストラクタで {@link #referenceType()} をキーとしたディスパッチ表を構築する。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.3 完全一致。
 *
 * @param <V> 機能固有の visibility 型 ({@link StandardVisibility} に正規化される前の値)
 */
public interface ContentVisibilityResolver<V> {

    /**
     * この Resolver が担当する {@link ReferenceType} を返す。
     *
     * @return Resolver の担当 reference_type (非 null)
     */
    ReferenceType referenceType();

    /**
     * 単発判定。
     *
     * <p>N+1 を避けるため、複数 ID の判定では {@link #filterAccessible(Collection, Long)}
     * を用いること。
     *
     * @param contentId    判定対象の contentId
     * @param viewerUserId 閲覧者の userId ({@code null} 可、未認証時)
     * @return 閲覧可能なら true
     */
    boolean canView(Long contentId, Long viewerUserId);

    /**
     * バッチ判定。
     *
     * <p>実装は SQL 数 ≦ 2 で完結すべき (1 回の SELECT で必要なメタデータを一括取得し、
     * メモリ上で判定する)。要素順は保証しない。
     *
     * @param contentIds   判定対象の contentId 集合
     * @param viewerUserId 閲覧者の userId ({@code null} 可、未認証時)
     * @return アクセス可能な contentId の Set。空でもよいが {@code null} は返さない
     */
    Set<Long> filterAccessible(Collection<Long> contentIds, Long viewerUserId);

    /**
     * 詳細判定理由を返すデバッグ・監査用 API (任意実装)。
     *
     * <p>デフォルト実装は {@link #canView(Long, Long)} の結果を {@link VisibilityDecision}
     * にラップして返す。個別 Resolver で {@link DenyReason} を厳密に分類したい場合は
     * オーバーライドすること。
     *
     * @param contentId    判定対象の contentId
     * @param viewerUserId 閲覧者の userId
     * @return 判定結果
     */
    default VisibilityDecision decide(Long contentId, Long viewerUserId) {
        boolean ok = canView(contentId, viewerUserId);
        return ok
            ? VisibilityDecision.allow(referenceType(), contentId)
            : VisibilityDecision.deny(referenceType(), contentId, DenyReason.UNSPECIFIED);
    }

    // -------------------------------------------------------------------
    // F09.15/16 S0 (2026-05-12): UUIDv7 reference 系の経路（F00-A 案）。
    //
    // 設計書: docs/features/F00_content_visibility_resolver.md §3.4
    // - 主キーが UUIDv7 (BINARY(16)) の reference_type 用オーバーロード
    // - 既存 Long 系 API は無変更 (後方互換)
    // - デフォルト実装は UnsupportedOperationException で fail-fast
    //   (各 Resolver は自身の {@link ReferenceType#idKind()} に応じて
    //    どちらか片方のみオーバーライドすれば良い)
    // -------------------------------------------------------------------

    /**
     * 単発判定 (UUIDv7 reference 系).
     *
     * <p>{@link ReferenceType#idKind()} が {@link ReferenceType.IdKind#UUID_V7}
     * を返す reference_type に対応する Resolver は本メソッドをオーバーライドする。
     * BIGINT 経路の Resolver はデフォルト実装のままで構わない。</p>
     *
     * <p><strong>命名規約</strong>: Long 版の {@link #canView(Long, Long)} と
     * 同名にすると {@code canView(null, ...)} 呼び出しや Mockito {@code any()} で
     * オーバーロード解決があいまいになるため、敢えて別名 {@code canViewUuid}
     * とした (F09.15/16 S0)。</p>
     *
     * @param contentId    判定対象の UUIDv7 contentId
     * @param viewerUserId 閲覧者の userId ({@code null} 可、未認証時)
     * @return 閲覧可能なら true
     * @throws UnsupportedOperationException この Resolver が UUID 経路をサポートしない場合
     */
    default boolean canViewUuid(UUID contentId, Long viewerUserId) {
        throw new UnsupportedOperationException(
            "Resolver " + getClass().getName()
                + " (referenceType=" + referenceType()
                + ", idKind=" + referenceType().idKind()
                + ") does not implement UUID-based canView");
    }

    /**
     * バッチ判定 (UUIDv7 reference 系).
     *
     * @param contentIds   判定対象の UUIDv7 contentId 集合
     * @param viewerUserId 閲覧者の userId ({@code null} 可)
     * @return アクセス可能な contentId の Set。空でもよいが {@code null} は返さない
     * @throws UnsupportedOperationException この Resolver が UUID 経路をサポートしない場合
     */
    default Set<UUID> filterAccessibleUuid(Collection<UUID> contentIds, Long viewerUserId) {
        throw new UnsupportedOperationException(
            "Resolver " + getClass().getName()
                + " (referenceType=" + referenceType()
                + ", idKind=" + referenceType().idKind()
                + ") does not implement UUID-based filterAccessible");
    }

    /**
     * 詳細判定 (UUIDv7 reference 系).
     *
     * <p>VisibilityDecision は contentId を Long で保持するため、本デフォルト実装では
     * contentId 値を {@code null} にして UUID は別経路で記録する。
     * UUID 専用の VisibilityDecision 拡張は後続フェーズで検討する。</p>
     *
     * <p><strong>命名規約</strong>: Long 版の {@link #decide(Long, Long)} と
     * 同名にするとオーバーロード解決があいまいになるため別名にした (F09.15/16 S0)。</p>
     *
     * @param contentId    判定対象の UUIDv7 contentId
     * @param viewerUserId 閲覧者の userId
     * @return 判定結果
     */
    default VisibilityDecision decideUuid(UUID contentId, Long viewerUserId) {
        boolean ok = canViewUuid(contentId, viewerUserId);
        // UUID は VisibilityDecision の Long contentId に乗せられないため null とする
        // (allow/deny の事実のみ伝える)。詳細な UUID 値は呼び出し元ログで補完する。
        return ok
            ? VisibilityDecision.allow(referenceType(), null)
            : VisibilityDecision.deny(referenceType(), null, DenyReason.UNSPECIFIED);
    }
}
