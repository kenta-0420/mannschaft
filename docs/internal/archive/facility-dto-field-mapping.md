# BookingDetailResponse フィールドマッピング一覧（FE対応用）

## 変更概要

`BookingDetailResponse` を 28フィールドのフラット構造から、論理グループ（サブDTO）にネストした設計に刷新。

- **旧:** トップレベルに 28フィールドが並列
- **新:** トップレベル 10個（`id`, `status`, `facility`, `schedule`, `usage`, `fee`, `approval`, `lifecycle`, `equipment`, `audit`）

---

## フィールドパス変換表

| 旧パス（廃止） | 新パス | サブDTO型 |
|---|---|---|
| `$.id` | `$.id` | Long（変更なし） |
| `$.status` | `$.status` | String（変更なし） |
| `$.facilityId` | `$.facility.facilityId` | BookingFacilityDto |
| `$.facilityName` | `$.facility.facilityName` | BookingFacilityDto |
| `$.bookedBy` | `$.facility.bookedBy` | BookingFacilityDto |
| `$.createdByAdmin` | `$.facility.createdByAdmin` | BookingFacilityDto |
| `$.bookingDate` | `$.schedule.bookingDate` | BookingScheduleDto |
| `$.checkOutDate` | `$.schedule.checkOutDate` | BookingScheduleDto |
| `$.stayNights` | `$.schedule.stayNights` | BookingScheduleDto |
| `$.timeFrom` | `$.schedule.timeFrom` | BookingScheduleDto |
| `$.timeTo` | `$.schedule.timeTo` | BookingScheduleDto |
| `$.slotCount` | `$.schedule.slotCount` | BookingScheduleDto |
| `$.purpose` | `$.usage.purpose` | BookingUsageDto |
| `$.attendeeCount` | `$.usage.attendeeCount` | BookingUsageDto |
| `$.usageFee` | `$.fee.usageFee` | BookingFeeDto |
| `$.equipmentFee` | `$.fee.equipmentFee` | BookingFeeDto |
| `$.totalFee` | `$.fee.totalFee` | BookingFeeDto |
| `$.approvedBy` | `$.approval.approvedBy` | BookingApprovalDto |
| `$.approvedAt` | `$.approval.approvedAt` | BookingApprovalDto |
| `$.adminComment` | `$.approval.adminComment` | BookingApprovalDto |
| `$.checkedInAt` | `$.lifecycle.checkedInAt` | BookingLifecycleDto |
| `$.completedAt` | `$.lifecycle.completedAt` | BookingLifecycleDto |
| `$.cancelledAt` | `$.lifecycle.cancelledAt` | BookingLifecycleDto |
| `$.cancelledBy` | `$.lifecycle.cancelledBy` | BookingLifecycleDto |
| `$.cancellationReason` | `$.lifecycle.cancellationReason` | BookingLifecycleDto |
| `$.equipment` | `$.equipment` | List<BookingEquipmentResponse>（変更なし） |
| `$.createdAt` | `$.audit.createdAt` | BookingAuditDto |
| `$.updatedAt` | `$.audit.updatedAt` | BookingAuditDto |

---

## サブDTO定義

### BookingFacilityDto
```json
{
  "facilityId": 1,
  "facilityName": "会議室A",
  "bookedBy": 100,
  "createdByAdmin": null
}
```

### BookingScheduleDto
```json
{
  "bookingDate": "2026-05-01",
  "checkOutDate": null,
  "stayNights": 0,
  "timeFrom": "10:00:00",
  "timeTo": "12:00:00",
  "slotCount": 4
}
```

### BookingUsageDto
```json
{
  "purpose": "打ち合わせ",
  "attendeeCount": 5
}
```

### BookingFeeDto
```json
{
  "usageFee": 2000,
  "equipmentFee": 0,
  "totalFee": 2000
}
```

### BookingApprovalDto
```json
{
  "approvedBy": null,
  "approvedAt": null,
  "adminComment": null
}
```

### BookingLifecycleDto
```json
{
  "checkedInAt": null,
  "completedAt": null,
  "cancelledAt": null,
  "cancelledBy": null,
  "cancellationReason": null
}
```

### BookingAuditDto
```json
{
  "createdAt": "2026-05-01T10:00:00",
  "updatedAt": "2026-05-01T10:00:00"
}
```

---

## 注意事項

- `@JsonInclude(Include.NON_NULL)` により、null のサブDTOや null フィールドはレスポンスに含まれない
- `$.facility.facilityName` は現状 `null`（mapper で ignore）。将来的に service 層から設定する場合は `withFacilityName()` 等のメソッドを追加すること
- `equipment` の後設定は `withEquipment(List<BookingEquipmentResponse>)` ファクトリメソッドを使用すること
