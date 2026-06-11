package com.mannschaft.app.organization.exception;

/**
 * 組織が存在しない、論理削除済み、または閲覧権限が無くエニュメレーション対策として
 * 隠蔽する場合にスローする例外。
 *
 * <p>F15.4 組織内チーム（店舗）検索の権限判定で使用する。
 * 設計書 {@code docs/features/F15.4_team_store_search_within_org.md §6.2} のとおり、
 * 非 PUBLIC 組織を未ログイン者または非メンバーが参照したケースでも本例外を投げ、
 * 上位レイヤ（コントローラ）で一律 404 として返すことで組織の存在自体を漏らさない。</p>
 *
 * <p>メッセージは固定文字列 {@code "Organization not found"}。
 * 内部状態（非公開か削除済みか）を区別する情報は意図的に含めない。</p>
 */
public class OrganizationNotFoundException extends RuntimeException {

    private static final String MESSAGE = "Organization not found";

    public OrganizationNotFoundException() {
        super(MESSAGE);
    }
}
