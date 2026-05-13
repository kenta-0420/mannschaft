package com.mannschaft.app.weather.util;

import java.util.HashMap;
import java.util.Map;

/**
 * F02.10 天気ウィジェット — WeatherAPI.com の {@code condition.code}（int）を
 * フロントエンド向けの {@code icon_key}（String）に変換する static ユーティリティ。
 *
 * <p>設計書 §13.5: 未知のコードは {@code "cloudy"} にフォールバック。</p>
 *
 * <p>セキュリティ: condition.code はサードパーティ API の整数値のみ。
 * ログには icon_key のみ出力し、API キー等は一切含まない。</p>
 */
public final class WeatherConditionMapper {

    /** WeatherAPI.com の condition.code → icon_key マッピング（設計書 §13.5 準拠）。 */
    private static final Map<Integer, String> CODE_TO_ICON_KEY;

    static {
        Map<Integer, String> map = new HashMap<>();

        // sunny: 晴れ
        map.put(1000, "sunny");

        // partly_cloudy: 一部曇り
        map.put(1003, "partly_cloudy");

        // cloudy: 曇り
        map.put(1006, "cloudy");
        map.put(1009, "cloudy");

        // overcast: どんよりした曇り・霧
        map.put(1030, "overcast");
        map.put(1135, "overcast");
        map.put(1147, "overcast");

        // mist: 小雨・霧雨・霧混じりの雨（弱〜中程度）
        map.put(1063, "mist");
        map.put(1072, "mist");
        map.put(1150, "mist");
        map.put(1153, "mist");
        map.put(1168, "mist");
        map.put(1171, "mist");
        map.put(1180, "mist");
        map.put(1183, "mist");
        map.put(1186, "mist");
        map.put(1189, "mist");
        map.put(1192, "mist");
        map.put(1195, "mist");
        map.put(1198, "mist");
        map.put(1240, "mist");

        // snow: 雪
        map.put(1066, "snow");
        map.put(1114, "snow");
        map.put(1117, "snow");
        map.put(1210, "snow");
        map.put(1213, "snow");
        map.put(1216, "snow");
        map.put(1219, "snow");
        map.put(1222, "snow");
        map.put(1225, "snow");
        map.put(1255, "snow");
        map.put(1258, "snow");

        // sleet: みぞれ
        map.put(1069, "sleet");
        map.put(1204, "sleet");
        map.put(1207, "sleet");
        map.put(1249, "sleet");
        map.put(1252, "sleet");

        // thunderstorm: 雷雨（雷系コード）
        map.put(1087, "thunderstorm");
        map.put(1273, "thunderstorm");
        map.put(1276, "thunderstorm");
        map.put(1279, "thunderstorm");
        map.put(1282, "thunderstorm");

        // heavy_rain: 大雨（mist 系コードのうち豪雨クラスを上書き）
        // Map.put で後勝ちになるため、heavy_rain を最後に登録して優先させる。
        map.put(1201, "heavy_rain");
        map.put(1243, "heavy_rain");
        map.put(1246, "heavy_rain");

        CODE_TO_ICON_KEY = Map.copyOf(map);
    }

    private WeatherConditionMapper() {
    }

    /**
     * WeatherAPI.com の condition.code を icon_key に変換する。
     *
     * @param conditionCode WeatherAPI.com が返す天気コード
     * @return 対応する icon_key。未知のコードの場合は {@code "cloudy"}
     */
    public static String toIconKey(int conditionCode) {
        return CODE_TO_ICON_KEY.getOrDefault(conditionCode, "cloudy");
    }
}
