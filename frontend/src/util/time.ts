// 시간 표시. 서버는 OffsetDateTime(+09:00) 문자열을 보내고, 화면은 브라우저 시간대와 상관없이 한국 시간으로 보여 준다.

const SEOUL = 'Asia/Seoul'

const dateTimeFormat = new Intl.DateTimeFormat('ko-KR', {
  timeZone: SEOUL, month: 'long', day: 'numeric', weekday: 'short', hour: '2-digit', minute: '2-digit', hour12: false,
})
const timeFormat = new Intl.DateTimeFormat('ko-KR', { timeZone: SEOUL, hour: '2-digit', minute: '2-digit', hour12: false })
const isoDateFormat = new Intl.DateTimeFormat('en-CA', { timeZone: SEOUL, year: 'numeric', month: '2-digit', day: '2-digit' })

/** "11월 18일 (수) 09:30" */
export function formatDateTime(offsetDateTime: string): string {
  return dateTimeFormat.format(new Date(offsetDateTime))
}

/** "09:30" */
export function formatTime(offsetDateTime: string): string {
  return timeFormat.format(new Date(offsetDateTime))
}

/** "2026-11-18" → "11월 18일 (수)" */
export function formatSaleDate(isoDate: string): string {
  const [year, month, day] = isoDate.split('-').map(Number)
  const weekday = new Intl.DateTimeFormat('ko-KR', { weekday: 'short', timeZone: 'UTC' }).format(new Date(Date.UTC(year, month - 1, day)))
  return `${month}월 ${day}일 (${weekday})`
}

/** 한국 기준 오늘 "yyyy-MM-dd" */
export function todayInSeoul(now: Date = new Date()): string {
  return isoDateFormat.format(now)
}

/** "yyyy-MM-dd" 에 날짜 더하기 (시간대 영향 없게 UTC 로 계산) */
export function addDays(isoDate: string, days: number): string {
  const [year, month, day] = isoDate.split('-').map(Number)
  return new Date(Date.UTC(year, month - 1, day + days)).toISOString().slice(0, 10)
}

export function formatPrice(won: number): string {
  return `${won.toLocaleString('ko-KR')}원`
}
