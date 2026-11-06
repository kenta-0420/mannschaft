package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.dto.CreateEmergencyClosureRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * 臨時休業通知の要求内容を正規化して SHA-256 を求める（CMP-261104-0334）。
 *
 * <p>同じ冪等キーで再送された要求が「先に受け付けた要求と同じ内容か」を判別するための照合値。
 * 対象は送信結果を左右する全項目（期間・時間帯・理由・件名・本文・予約取消フラグ）で、
 * 各項目を「長さ:値」で連結してから求める（区切り文字を含む値どうしが衝突しないようにするため）。
 * 日付は ISO（{@code yyyy-MM-dd}）、時刻は {@code HH:mm:ss}、未指定（終日休業）は長さ {@code -1} で表す。
 * 文字列は受け取った値をそのまま使う（空白や改行の違いも内容の違いとして扱う）。</p>
 */
final class EmergencyClosureRequestHasher {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private EmergencyClosureRequestHasher() {
    }

    /**
     * 要求内容の SHA-256 を16進64文字（小文字）で返す。
     *
     * @param request 臨時休業通知リクエスト
     * @return 正規化した要求内容の SHA-256
     */
    static String hash(CreateEmergencyClosureRequest request) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, date(request.getStartDate()));
        append(canonical, date(request.getEndDate()));
        append(canonical, time(request.getStartTime()));
        append(canonical, time(request.getEndTime()));
        append(canonical, request.getReason());
        append(canonical, request.getSubject());
        append(canonical, request.getMessageBody());
        append(canonical, Boolean.toString(request.isCancelReservations()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 は Java SE の必須アルゴリズムのため到達しない。到達したら実行環境の欠陥として落とす。
            throw new IllegalStateException("SHA-256 が利用できません", e);
        }
    }

    private static void append(StringBuilder canonical, String value) {
        if (value == null) {
            canonical.append("-1:");
        } else {
            canonical.append(value.length()).append(':').append(value);
        }
        canonical.append('\n');
    }

    private static String date(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static String time(LocalTime time) {
        return time == null ? null : TIME.format(time);
    }
}
