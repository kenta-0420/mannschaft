package com.mannschaft.app.role.dto;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.RoleErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 承諾・辞退の対象となる招待の指し方（CMP-260826-1457）。
 *
 * <p>招待は平文トークンを保存しないため、トークン入口は金庫（{@code SecretTokenVault}）でハッシュ化した値
 * （{@link ByTokenHash}）で指す。ID 入口（{@link ById}）は平文を持ち回らないための入口で、宛先付きの招待だけを
 * 受け付ける。宛先不一致などのエラー方針は各型が持つ。</p>
 */
public sealed interface InviteLocator permits InviteLocator.ByTokenHash, InviteLocator.ById {

    /** 宛先ユーザーが一致しなかったときに投げる例外。 */
    BusinessException targetMismatch();

    /**
     * 有効性の判定より前に、宛先本人でない呼び出しを隠すか。
     *
     * <p>ID は推測できるため、宛先本人でない・共有リンク型・存在しない ID は、招待の状態（上限到達・期限切れなど）に
     * 関わらず同じ応答にする（存在オラクルを作らない）。</p>
     */
    default boolean concealsNonTarget() {
        return false;
    }

    /** 招待トークンのハッシュ（SHA-256 hex64）で指す。宛先不一致は 403 ROLE_009（IDOR 防止）。 */
    record ByTokenHash(String tokenHash) implements InviteLocator {
        @Override
        public BusinessException targetMismatch() {
            return new BusinessException(RoleErrorCode.ROLE_009, HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 招待 ID で指す。宛先付きの招待だけを受け付け、宛先違い・共有リンク型・存在しない ID はすべて
     * 404 ROLE_002 にする。
     */
    record ById(Long id) implements InviteLocator {
        @Override
        public BusinessException targetMismatch() {
            return new BusinessException(RoleErrorCode.ROLE_002);
        }

        @Override
        public boolean concealsNonTarget() {
            return true;
        }
    }
}
