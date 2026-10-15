# BlogPostResponse フィールドマッピング（FE対応用）

Wave 1 第三陣: `BlogPostResponse` ネスト化リファクタリング

## 旧構造 → 新構造 パス変換一覧

| 旧 JSON パス | 新 JSON パス |
|---|---|
| `$.id` | `$.id`（変更なし） |
| `$.teamId` | `$.scope.teamId` |
| `$.organizationId` | `$.scope.organizationId` |
| `$.userId` | `$.scope.userId` |
| `$.authorId` | `$.scope.authorId` |
| `$.title` | `$.content.title` |
| `$.slug` | `$.content.slug` |
| `$.body` | `$.content.body` |
| `$.excerpt` | `$.content.excerpt` |
| `$.coverImageUrl` | `$.content.coverImageUrl` |
| `$.postType` | `$.meta.postType` |
| `$.visibility` | `$.meta.visibility` |
| `$.priority` | `$.meta.priority` |
| `$.status` | `$.meta.status` |
| `$.pinned` | `$.meta.pinned` |
| `$.allowComments` | `$.meta.allowComments` |
| `$.seriesId` | `$.series.seriesId` |
| `$.seriesOrder` | `$.series.seriesOrder` |
| `$.viewCount` | `$.stats.viewCount` |
| `$.readingTimeMinutes` | `$.stats.readingTimeMinutes` |
| `$.mitayo` | `$.stats.mitayo` |
| `$.mitayoCount` | `$.stats.mitayoCount` |
| `$.publishedAt` | `$.audit.publishedAt` |
| `$.version` | `$.audit.version` |
| `$.createdAt` | `$.audit.createdAt` |
| `$.updatedAt` | `$.audit.updatedAt` |
| `$.tags` | `$.tags`（変更なし） |

## 新しいトップレベル構造

```json
{
  "id": 123,
  "scope": {
    "teamId": 1,
    "organizationId": null,
    "userId": null,
    "authorId": 10
  },
  "content": {
    "title": "記事タイトル",
    "slug": "article-slug",
    "body": "本文...",
    "excerpt": "抜粋",
    "coverImageUrl": "https://..."
  },
  "meta": {
    "postType": "BLOG",
    "visibility": "MEMBERS_ONLY",
    "priority": "NORMAL",
    "status": "PUBLISHED",
    "pinned": false,
    "allowComments": true
  },
  "series": {
    "seriesId": null,
    "seriesOrder": null
  },
  "stats": {
    "viewCount": 42,
    "readingTimeMinutes": 3,
    "mitayo": false,
    "mitayoCount": 0
  },
  "tags": [
    { "id": 1, "name": "タグ名", "color": "#FF0000" }
  ],
  "audit": {
    "publishedAt": "2026-05-25T10:00:00",
    "version": 1,
    "createdAt": "2026-05-25T09:00:00",
    "updatedAt": "2026-05-25T10:00:00"
  }
}
```

## 注意事項

- `@JsonInclude(JsonInclude.Include.NON_NULL)` により null フィールドは JSON に含まれない
- `series` フィールドは seriesId/seriesOrder が両方 null の場合、NON_NULL ではオブジェクト自体が出力されるので注意
- `tags` は常に空配列として初期化される（タグ紐付けは別クエリ）
