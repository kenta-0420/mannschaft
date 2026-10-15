/**
 * F00 共通可視性基盤 — 機能側 enum を {@link com.mannschaft.app.common.visibility.StandardVisibility}
 * に正規化する Mapper 群。
 *
 * <p>本パッケージの責務:
 * <ul>
 *   <li>各機能固有の Visibility enum (例: {@code cms.Visibility},
 *       {@code event.entity.EventVisibility} 等) を、機能横断の標準値である
 *       {@link com.mannschaft.app.common.visibility.StandardVisibility} に
 *       一意写像する純粋関数を提供する</li>
 *   <li>Mapper はすべて static method のみを公開し、private constructor で
 *       インスタンス化を禁ずる util クラスとする</li>
 *   <li>switch は exhaustive とし、コンパイラ警告ゼロを維持する</li>
 *   <li>状態を持たず、副作用も持たない。Repository / DB / Spring Bean には依存しない</li>
 * </ul>
 *
 * <p>マスター確定方針 (memory/project_f00_phase_a_decisions.md C-2 / 2026-05-04):
 * SUPPORTERS_AND_ABOVE 系の値 (SUPPORTERS_AND_ABOVE / SUPPORTERS_ONLY /
 * TEAM_MEMBERS_SUPPORTERS 等) はすべて
 * {@link com.mannschaft.app.common.visibility.StandardVisibility#SUPPORTERS_AND_ABOVE}
 * に正規化する (包含: GUEST 以外の全認証メンバー)。
 * SUPPORTER 単独を意図する機能は現時点なく、StandardVisibility の 9 値打ち止め方針
 * (設計書 §15 D-2) を維持する。
 *
 * <p>機能側 enum の改廃手順 (設計書 §5.4):
 * <ol>
 *   <li>機能側 enum に値を追加・削除する場合、必ず対応する Mapper の switch を更新する</li>
 *   <li>switch が exhaustive でなくなるとコンパイルエラーとなるため、漏れは検出可能</li>
 *   <li>新規値が StandardVisibility のいずれにも対応しない場合は、
 *       {@link com.mannschaft.app.common.visibility.StandardVisibility#CUSTOM} へ写像し、
 *       Resolver の {@code evaluateCustom} で個別ハンドリングする</li>
 *   <li>CUSTOM 比率が 30% を超えた場合、StandardVisibility 値追加の議題化が必須
 *       (設計書 §5.1.4)</li>
 * </ol>
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 / §5.4。
 */
package com.mannschaft.app.common.visibility.mapping;
