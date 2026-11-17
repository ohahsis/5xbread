// C-02 판매일 빵 목록 + C-03 실시간 잔여 + C-04 예약.
//
// 화면 구성: 판매일 고르기 → 그날 빵 표(잔여는 실시간) → 수량 입력 → 픽업 시간 → 예약하기
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { api, withQuery } from '../api/client'
import { messageOf } from '../api/problem'
import type { PickupSlot, ReservationCreated, SaleBread, SaleDateStatus } from '../api/types'
import { useSession } from '../auth/auth'
import { breadsQueryKey, useLiveBreads } from '../realtime/useLiveBreads'
import { IdempotentSubmitter, type ReservationDraft } from '../reservation/idempotentSubmit'
import { reservationErrorView, type ReservationErrorView } from '../reservation/reservationErrors'
import { addDays, formatDateTime, formatPrice, formatSaleDate, formatTime, todayInSeoul } from '../util/time'
import s from './BreadListPage.module.css'

/** 판매일 고르기에 보여 줄 기간 (오늘부터) */
const DAYS_AHEAD = 14

const SALE_DATE_STATUS_LABEL: Record<SaleDateStatus['status'], string> = {
  NOT_OPEN: '예약 전',
  OPEN: '예약 중',
  CLOSED: '마감',
  HOLIDAY: '휴무',
}

export function BreadListPage() {
  const [params, setParams] = useSearchParams()
  const today = todayInSeoul()
  const saleDates = useQuery({
    queryKey: ['sale-dates', today],
    queryFn: () => api<SaleDateStatus[]>(withQuery('/api/v1/sale-dates', { from: today, to: addDays(today, DAYS_AHEAD - 1) })),
  })

  // 고른 날짜는 주소(?date=)에 둔다. 없으면 예약 중인 첫 날, 그것도 없으면 오늘
  const firstOpen = saleDates.data?.find((day) => day.status === 'OPEN')?.saleDate
  const date = params.get('date') ?? firstOpen ?? (saleDates.isLoading ? null : today)

  return (
    <>
      <h1>빵 예약</h1>
      {saleDates.error && <p className="notice error">{messageOf(saleDates.error)}</p>}
      <div className={s.dates}>
        {saleDates.data?.map((day) => (
          <button
            key={day.saleDate}
            type="button"
            className={day.saleDate === date ? `${s.date} ${s.selected}` : s.date}
            onClick={() => setParams({ date: day.saleDate })}
          >
            <span>{formatSaleDate(day.saleDate)}</span>
            <span className={s.dateStatus}>{SALE_DATE_STATUS_LABEL[day.status]}</span>
          </button>
        ))}
      </div>
      {/* key: 날짜를 바꾸면 패널을 새로 만든다 (입력한 수량·Idempotency-Key·구독을 모두 새로) */}
      {date && <SaleDayPanel key={date} date={date} saleDate={saleDates.data?.find((day) => day.saleDate === date)} />}
    </>
  )
}

function SaleDayPanel({ date, saleDate }: { date: string; saleDate: SaleDateStatus | undefined }) {
  const session = useSession()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { saleBreads, breads, live, isLoading, error } = useLiveBreads(date)
  const isOpen = saleBreads?.status === 'OPEN'

  const pickupSlots = useQuery({
    queryKey: ['pickup-slots', date],
    queryFn: () => api<PickupSlot[]>(`/api/v1/sale-dates/${date}/pickup-slots`),
    enabled: isOpen,
  })

  const [quantities, setQuantities] = useState<Record<number, number>>({})
  const [pickupSlotId, setPickupSlotId] = useState<number | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [failure, setFailure] = useState<ReservationErrorView | null>(null)
  const submitterRef = useRef(new IdempotentSubmitter<ReservationCreated>())

  const items = Object.entries(quantities)
    .filter(([, quantity]) => quantity > 0)
    .map(([breadId, quantity]) => ({ breadId: Number(breadId), quantity }))
  const total = items.reduce((sum, item) => sum + item.quantity * (breads.find((b) => b.breadId === item.breadId)?.price ?? 0), 0)
  const slotId = pickupSlotId ?? pickupSlots.data?.[0]?.pickupSlotId ?? null
  const canSubmit = isOpen && session !== null && items.length > 0 && slotId !== null && !submitting

  const onSubmit = async () => {
    if (slotId === null) return
    const draft: ReservationDraft = { saleDate: date, pickupSlotId: slotId, items }
    setSubmitting(true)
    setFailure(null)
    try {
      const created = await submitterRef.current.submit(draft, (body, idempotencyKey) =>
        api<ReservationCreated>('/api/v1/reservations', { method: 'POST', body, headers: { 'Idempotency-Key': idempotencyKey } }))
      void queryClient.invalidateQueries({ queryKey: breadsQueryKey(date) })    // 내 예약 수량(myReservedQty) 갱신
      void queryClient.invalidateQueries({ queryKey: ['my-reservations'] })
      navigate(`/reservations/${created.reservationId}?created=1`)
    } catch (e) {
      setFailure(reservationErrorView(e))
    } finally {
      setSubmitting(false)
    }
  }

  if (isLoading) return <p className="page-message">빵 목록을 불러오는 중…</p>
  if (error) return <p className="notice error">{messageOf(error)}</p>
  if (!saleBreads) return null

  return (
    <section>
      <div className={s.summary}>
        <span>{formatSaleDate(date)} · {SALE_DATE_STATUS_LABEL[saleBreads.status]}</span>
        <span className={live ? s.live : s.offline}>{live ? '● 실시간' : '○ 실시간 연결 끊김'}</span>
      </div>
      {saleDate?.status === 'NOT_OPEN' && saleDate.openAt && (
        <p className="notice warning">{formatDateTime(saleDate.openAt)}부터 예약할 수 있습니다.</p>
      )}
      {saleBreads.status === 'HOLIDAY' && <p className="page-message">휴무일입니다.</p>}

      {breads.length > 0 && (
        <table className={s.table}>
          <thead>
            <tr>
              <th>빵</th>
              <th className={s.num}>가격</th>
              <th className={s.num}>남은 수량</th>
              <th className={s.num}>예약 수량</th>
            </tr>
          </thead>
          <tbody>
            {breads.map((bread) => (
              <BreadRow
                key={bread.breadId}
                bread={bread}
                quantity={quantities[bread.breadId] ?? 0}
                editable={isOpen && session !== null}
                error={failure?.byBread.get(bread.breadId)}
                onChange={(quantity) => setQuantities((prev) => ({ ...prev, [bread.breadId]: quantity }))}
              />
            ))}
          </tbody>
        </table>
      )}

      {isOpen && (
        <div className={s.order}>
          {!session && <p className="notice warning"><Link to={`/login?next=${encodeURIComponent(`/breads?date=${date}`)}`}>로그인</Link>하면 예약할 수 있습니다.</p>}
          {session && (
            <>
              <label className={s.slot}>
                픽업 시간
                <select value={slotId ?? ''} onChange={(e) => setPickupSlotId(Number(e.target.value))}>
                  {pickupSlots.data?.map((slot) => (
                    <option key={slot.pickupSlotId} value={slot.pickupSlotId}>{formatTime(slot.pickupAt)}</option>
                  ))}
                </select>
              </label>
              {failure && <p className="notice error">{failure.message}</p>}
              <div className={s.submit}>
                <span>합계 <strong>{formatPrice(total)}</strong></span>
                <button className="button" disabled={!canSubmit} onClick={onSubmit}>
                  {submitting ? '예약하는 중…' : failure?.retryable ? '다시 시도' : '예약하기'}
                </button>
              </div>
            </>
          )}
        </div>
      )}
    </section>
  )
}

function BreadRow({ bread, quantity, editable, error, onChange }: {
  bread: SaleBread
  quantity: number
  editable: boolean
  error: string | undefined
  onChange: (quantity: number) => void
}) {
  // 더 고를 수 있는 최대: 잔여, 1인 한도에서 이미 예약한 만큼 뺀 값 중 작은 쪽 (최종 판단은 서버)
  const limitLeft = bread.perUserLimit === null ? Infinity : bread.perUserLimit - (bread.myReservedQty ?? 0)
  const max = Math.max(0, Math.min(bread.remaining, limitLeft))
  const soldOut = bread.status === 'SOLD_OUT'

  return (
    <tr className={soldOut ? s.soldOut : undefined}>
      <td>
        <div>{bread.name}</div>
        <div className={s.meta}>
          {bread.perUserLimit !== null && <span>1인 {bread.perUserLimit}개</span>}
          {bread.myReservedQty !== null && bread.myReservedQty > 0 && <span>내 예약 {bread.myReservedQty}개</span>}
        </div>
        {error && <p className="field-error">{error}</p>}
      </td>
      <td className={s.num}>{formatPrice(bread.price)}</td>
      <td className={s.num}>{soldOut ? <span className="badge">품절</span> : `${bread.remaining}개`}</td>
      <td className={s.num}>
        {editable ? (
          <input
            className={s.quantity}
            type="number"
            min={0}
            max={max}
            value={quantity}
            disabled={soldOut && quantity === 0}
            onChange={(e) => onChange(Math.max(0, Math.floor(Number(e.target.value) || 0)))}
          />
        ) : '-'}
      </td>
    </tr>
  )
}
