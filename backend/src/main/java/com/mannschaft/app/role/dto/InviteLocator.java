package com.mannschaft.app.role.dto;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.RoleErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 承諾・辞退の対象となる招待の指し方（CMP-260826-1457）。
 *
 * <p>main では招待トークン文字列（{@link ByToken}）のみ。ID 入口（#4348）は許可型を足し、
 * 宛先不一致などのエラー方針を各型が持つ。</p>
 */
public sealed interface InviteLocator permits InviteLocator.ByToken {

    /** 宛先ユーザーが一致しなかったときに投げる例外。 */
    BusinessException targetMismatch();

    /** 招待トークン文字列で指す。宛先不一致は 403 ROLE_009（IDOR 防止）。 */
    record ByToken(String token) implements InviteLocator {
        @Override
        public BusinessException targetMismatch() {
            return new BusinessException(RoleErrorCode.ROLE_009, HttpStatus.FORBIDDEN);
        }
    }
}
