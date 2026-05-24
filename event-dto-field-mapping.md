# EventDetailResponse フィールドマッピング表（FE対応用）

## 概要

`EventDetailResponse` を 32 フラットフィールドから **トップレベル 8 個 + サブ DTO** に刷新した。

| トップレベルフィールド | 型 | 説明 |
|---|---|---|
| `id` | `Long` | イベントID |
| `scope` | `EventScopeDto` | スコープ情報 |
| `content` | `EventContentDto` | コンテンツ情報 |
| `venue` | `EventVenueDto` | 会場情報 |
| `registration` | `EventRegistrationDto` | 参加登録情報 |
| `meta` | `EventMetaDto` | メタ情報（ステータス・OGP等） |
| `rsvpSummary` | `EventRsvpSummaryResponse` | RSVPサマリー（nullable） |
| `audit` | `EventAuditDto` | 監査情報 |

---

## フィールドパス変換表（旧 → 新）

| 旧 JSON パス | 新 JSON パス | 型 |
|---|---|---|
| `$.id` | `$.id` | Long |
| `$.scopeType` | `$.scope.scopeType` | String |
| `$.scopeId` | `$.scope.scopeId` | Long |
| `$.scheduleId` | `$.scope.scheduleId` | Long（nullable）|
| `$.workflowRequestId` | `$.scope.workflowRequestId` | Long（nullable）|
| `$.slug` | `$.content.slug` | String |
| `$.subtitle` | `$.content.subtitle` | String（nullable）|
| `$.summary` | `$.content.summary` | String（nullable）|
| `$.coverImageKey` | `$.content.coverImageKey` | String（nullable）|
| `$.venueName` | `$.venue.venueName` | String（nullable）|
| `$.venueAddress` | `$.venue.venueAddress` | String（nullable）|
| `$.venueLatitude` | `$.venue.venueLatitude` | BigDecimal（nullable）|
| `$.venueLongitude` | `$.venue.venueLongitude` | BigDecimal（nullable）|
| `$.venueAccessInfo` | `$.venue.venueAccessInfo` | String（nullable）|
| `$.registrationStartsAt` | `$.registration.registrationStartsAt` | LocalDateTime（nullable）|
| `$.registrationEndsAt` | `$.registration.registrationEndsAt` | LocalDateTime（nullable）|
| `$.maxCapacity` | `$.registration.maxCapacity` | Integer（nullable）|
| `$.isApprovalRequired` | `$.registration.isApprovalRequired` | Boolean |
| `$.attendanceMode` | `$.registration.attendanceMode` | EventAttendanceMode |
| `$.preSurveyId` | `$.registration.preSurveyId` | Long（nullable）|
| `$.postSurveyId` | `$.registration.postSurveyId` | Long（nullable）|
| `$.registrationCount` | `$.registration.registrationCount` | Integer |
| `$.checkinCount` | `$.registration.checkinCount` | Integer |
| `$.status` | `$.meta.status` | String |
| `$.visibility` | `$.meta.visibility` | String |
| `$.ogpTitle` | `$.meta.ogpTitle` | String（nullable）|
| `$.ogpDescription` | `$.meta.ogpDescription` | String（nullable）|
| `$.ogpImageKey` | `$.meta.ogpImageKey` | String（nullable）|
| `$.createdBy` | `$.audit.createdBy` | Long（nullable）|
| `$.createdAt` | `$.audit.createdAt` | LocalDateTime |
| `$.updatedAt` | `$.audit.updatedAt` | LocalDateTime |
| `$.version` | `$.audit.version` | Long |
| `$.rsvpSummary` | `$.rsvpSummary` | EventRsvpSummaryResponse（nullable）|

---

## 注意事項

- `$.rsvpSummary` は `attendanceMode=RSVP` のときのみ非 null
- `@JsonInclude(NON_NULL)` が付いているため、null フィールドは JSON に出力されない
- サブ DTO は Java record 型（不変）
