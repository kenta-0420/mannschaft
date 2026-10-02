package com.mannschaft.app.schedule.dto;

import com.mannschaft.app.schedule.dto.GoogleCalendarStatusResponse.SyncErrorDetail;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 個人同期状態レスポンスDTO（GET /api/v1/me/google-calendar/personal-sync）。
 * 副作用なし・読み取り専用。未連携時は connected=false / active=false / personalSyncEnabled=false / email=null を返す。
 */
@Getter
@RequiredArgsConstructor
public class PersonalSyncStatusResponse {

    /** Google Calendar 連携が存在するか。 */
    private final boolean connected;

    /** 連携がアクティブか。未連携時は false。 */
    private final boolean active;

    /** 個人同期が有効か。未連携時は false。 */
    private final boolean personalSyncEnabled;

    /** 連携している Google アカウントのメールアドレス。未連携時は null。 */
    private final String googleAccountEmail;

    /** 最後に発生した同期エラーの詳細。エラーなし・未連携時は null。 */
    private final SyncErrorDetail lastSyncError;
}
