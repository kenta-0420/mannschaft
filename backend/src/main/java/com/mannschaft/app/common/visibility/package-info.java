/**
 * F00 コンテンツ可視性共通基盤 (ContentVisibilityResolver).
 *
 * <p>本パッケージは、機能横断で「ある userId が ある reference_type の content_id を
 * 閲覧可能か？」を一元判定するための共通基盤を提供する。
 *
 * <p>本パッケージの責務 (Phase A-1a スコープ):
 * <ul>
 *   <li>{@link com.mannschaft.app.common.visibility.StandardVisibility} —
 *       機能横断の標準可視性レベル (9 値)</li>
 *   <li>{@link com.mannschaft.app.common.visibility.ReferenceType} —
 *       横断的に参照されるコンテンツ種別 (19 値)</li>
 *   <li>{@link com.mannschaft.app.common.visibility.ContentStatus} —
 *       公開状態の標準カテゴリ (5 値)</li>
 *   <li>{@link com.mannschaft.app.common.visibility.DenyReason} —
 *       判定拒否理由のコード (7 値)</li>
 *   <li>{@link com.mannschaft.app.common.visibility.VisibilityDecision} —
 *       判定結果の値オブジェクト</li>
 *   <li>{@link com.mannschaft.app.common.visibility.ScopeKey} —
 *       スコープ識別の複合キー</li>
 *   <li>{@link com.mannschaft.app.common.visibility.VisibilityProjection} —
 *       Projection 共通インターフェース</li>
 * </ul>
 *
 * <p>Resolver IF / Checker / AbstractContentVisibilityResolver / Mapper / Repository は
 * 後続フェーズ (A-1b / A-1c / A-2a / A-2b 等) で別途配置される。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} v1.0
 * (be3bec47, main マージ済)。
 */
package com.mannschaft.app.common.visibility;
