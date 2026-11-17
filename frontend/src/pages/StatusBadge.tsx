import type { ReservationStatus } from '../api/types'
import s from './Reservations.module.css'

export const STATUS_LABEL: Record<ReservationStatus, string> = {
  RESERVED: '예약됨',
  PICKED_UP: '수령 완료',
  CANCELED: '취소됨',
  NO_SHOW: '노쇼',
}

export function StatusBadge({ status }: { status: ReservationStatus }) {
  return <span className={`badge ${s[`status_${status}`] ?? ''}`}>{STATUS_LABEL[status]}</span>
}
