/**
 * dayjs グローバル設定プラグイン。
 * utc / timezone / relativeTime プラグインをセットアップし、デフォルトロケールを ja に設定する。
 * useDatetime composable や useRelativeTime composable から利用される。
 */
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/ja'
import 'dayjs/locale/en'
import 'dayjs/locale/zh'
import 'dayjs/locale/ko'
import 'dayjs/locale/es'
import 'dayjs/locale/de'

dayjs.extend(utc)
dayjs.extend(timezone)
dayjs.extend(relativeTime)
dayjs.locale('ja')

export default defineNuxtPlugin(() => {
  return {
    provide: {
      dayjs,
    },
  }
})
