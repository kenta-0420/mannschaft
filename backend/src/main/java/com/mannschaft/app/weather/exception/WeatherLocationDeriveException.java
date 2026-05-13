package com.mannschaft.app.weather.exception;

import lombok.Getter;

/**
 * 郵便番号から居住地点を導出する際の失敗を表す例外。
 *
 * <p>設計書 §5.1 / §6.4 のエラーサブコードと 1:1 対応する。</p>
 */
@Getter
public class WeatherLocationDeriveException extends RuntimeException {

    private final ErrorCode errorCode;

    public WeatherLocationDeriveException(ErrorCode errorCode) {
        super(errorCode.name());
        this.errorCode = errorCode;
    }

    public WeatherLocationDeriveException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public enum ErrorCode {
        /** ユーザーの郵便番号が未登録（理論上発生せず、データ不整合のみ）。 */
        POSTAL_CODE_MISSING,
        /** 郵便番号が postal_codes マスタに該当しない（私書箱・新設地域・誤入力等）。 */
        POSTAL_CODE_NOT_FOUND,
        /** ユーザーの country_code が GeoNames 未対応国。 */
        COUNTRY_NOT_SUPPORTED
    }
}
