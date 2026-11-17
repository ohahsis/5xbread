// C-05 내 예약 목록. 최신순 + 상태 필터, 커서로 "더 보기".
// 예정·지난 예약 탭은 서버 파라미터가 아직 미정이라 두지 않는다 (decisions.md 6장 "C-05 예정·지난 예약 탭 (미정)")
import { useInfiniteQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router'
import { api, withQuery } from '../api/client'
import { messageOf } from '../api/problem'
import type { ReservationList, ReservationStatus } from '../api/types'
import { formatDateTime, formatPrice, formatSaleDate } from '../util/time'
import { StatusBadge, STATUS_LABEL } from './StatusBadge'
import s from './Reservations.module.css'

const PAGE_SIZE = 20
const STATUSES: ReservationStatus[] = ['RESERVED', 'PICKED_UP', 'CANCELED', 'NO_SHOW']

export function MyReservationsPage() {
  const [params, setParams] = useSearchParams()
  const status = (params.get('status') as ReservationStatus | null) ?? null

  const query = useInfiniteQuery({
    queryKey: ['my-reservations', status],
    queryFn: ({ pageParam }) =>
      api<ReservationList>(withQuery('/api/v1/members/me/reservations', { status, cursor: pageParam, size: PAGE_SIZE })),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => (lastPage.hasNext ? lastPage.nextCursor : null),
  })

  const reservations = query.data?.pages.flatMap((page) => page.items) ?? []

  return (
    <>
      <h1>내 예약</h1>
      <label className={s.filter}>
        상태
        <select value={status ?? ''} onChange={(e) => setParams(e.target.value ? { status: e.target.value } : {})}>
          <option value="">전체</option>
          {STATUSES.map((value) => <option key={value} value={value}>{STATUS_LABEL[value]}</option>)}
        </select>
      </label>

      {query.isLoading && <p className="page-message">불러오는 중…</p>}
      {query.error && <p className="notice error">{messageOf(query.error)}</p>}
      {query.isSuccess && reservations.length === 0 && <p className="page-message">예약이 없습니다. <Link to="/breads">빵 예약하러 가기</Link></p>}

      <ul className={s.list}>
        {reservations.map((reservation) => (
          <li key={reservation.reservationId}>
            <Link to={`/reservations/${reservation.reservationId}`} className={s.card}>
              <div className={s.cardHead}>
                <strong>{formatSaleDate(reservation.saleDate)}</strong>
                <StatusBadge status={reservation.status} />
              </div>
              <div className="muted">픽업 {formatDateTime(reservation.pickupAt)} · 예약번호 {reservation.reservationNo}</div>
              <div>
                {reservation.items.map((item) => `${item.name} ${item.quantity}개`).join(', ')}
                <span className={s.price}>{formatPrice(reservation.totalPrice)}</span>
              </div>
            </Link>
          </li>
        ))}
      </ul>

      {query.hasNextPage && (
        <button className="button secondary" disabled={query.isFetchingNextPage} onClick={() => query.fetchNextPage()}>
          {query.isFetchingNextPage ? '불러오는 중…' : '더 보기'}
        </button>
      )}
    </>
  )
}
