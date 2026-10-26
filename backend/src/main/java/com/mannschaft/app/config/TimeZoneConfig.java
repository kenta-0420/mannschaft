package com.mannschaft.app.config;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/**
 * JVM のデフォルトタイムゾーンを業務ローカル時刻の基準ゾーンに設定する（.claudecode.md §20）。
 *
 * <p>基準ゾーンは {@link UserZoneLocalDateTimeParser#SERVER_ZONE}（＝アプリ層の壁時計ゾーン）を
 * <b>唯一の正</b>として参照する。これにより</p>
 * <ul>
 *   <li>JVM 既定ゾーン（本クラス）</li>
 *   <li>壁時計 Clock（{@code ClockConfig#wallClock}）</li>
 *   <li>API 入力のパーサ（{@link UserZoneLocalDateTimeParser}）</li>
 * </ul>
 * <p>の三者が同一のソースを見る。片方だけ変わって判定が 9 時間ずれる事故が構造的に起こらない。</p>
 *
 * <p><b>なぜ環境変数で可変にしないのか:</b> ゾーンを実行時に差し替えられる「つまみ」にしても、
 * DB に既に書かれている {@code LocalDateTime} 列は旧ゾーンの壁時計のままであり、
 * 変更した瞬間に既存データの解釈が壊れる。ゾーンの多様化（テナント別 TZ）は
 * 格納形式・入力変換・判定基準を一体で設計し直す必要があり、
 * <b>CMP-023「時刻設計の全域是正＋テナント TZ 導入」</b>が受け皿である。
 * 中途半端に設定可能に見せることは「変えると壊れる嘘のつまみ」であり、ここでは行わない。</p>
 */
@Configuration
public class TimeZoneConfig {

    @PostConstruct
    public void initTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone(UserZoneLocalDateTimeParser.SERVER_ZONE));
    }
}
