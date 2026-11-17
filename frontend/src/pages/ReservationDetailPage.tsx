// C-05 예약 상세 + C-06 취소 + C-07 픽업 QR.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import QRCode from 'qrcode'
import { useEffect, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { api } from '../api/client'
import { messageOf } from '../api/problem'
import type { PickupCode, ReservationDetail } from '../api/types'
import { formatDateTime, formatPrice, formatSaleDate } from '../util/time'
import { StatusBadge } from './StatusBadge'
import s from './Reservations.module.css'

export function ReservationDetailPage() {
  const { reservationId } = useParams()
  const [params] = useSearchParams()
  const queryClient = useQueryClient()

  const detail = useQuery({
    queryKey: ['reservation', reservationId],
    queryFn: () => api<ReservationDetail>(`/api/v1/reservations/${reservationId}`),
  })

  const cancel = useMutation({
    mutationFn: () => api(`/api/v1/reservations/${reservationId}`, {
      method: 'PATCH',
      body: { status: 'CANCELED' },
      contentType: 'application/merge-patch+json',
    }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['reservation', reservationId] })
      void queryClient.invalidateQueries({ queryKey: ['my-reservations'] })
      void queryClient.invalidateQueries({ queryKey: ['sale-breads'] })
    },
  })

  if (detail.isLoading) return <p className="page-message">불러오는 중…</p>
  if (detail.error) return <p className="notice error">{messageOf(detail.error)}</p>
  const reservation = detail.data
  if (!reservation) return null

  const onCancel = () => {
    if (window.confirm('이 예약을 취소할까요?')) cancel.mutate()
  }

  return (
    <>
      <p><Link to="/reservations">← 내 예약</Link></p>
      {params.get('created') && reservation.status === 'RESERVED' && <p className="notice ok">예약했습니다.</p>}
      <div className={s.cardHead}>
        <h1>{formatSaleDate(reservation.saleDate)} 예약</h1>
        <StatusBadge status={reservation.status} />
      </div>
      <dl className={s.info}>
        <dt>예약번호</dt><dd>{reservation.reservationNo}</dd>
        <dt>픽업</dt><dd>{formatDateTime(reservation.pickupAt)}</dd>
        {reservation.canceledAt && <><dt>취소</dt><dd>{formatDateTime(reservation.canceledAt)}</dd></>}
      </dl>

      <table className={s.items}>
        <tbody>
          {reservation.items.map((item) => (
            <tr key={item.breadId}>
              <td>{item.name}</td>
              <td>{item.quantity}개</td>
              <td>{formatPrice(item.unitPrice * item.quantity)}</td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr><td>합계</td><td /><td>{formatPrice(reservation.totalPrice)}</td></tr>
        </tfoot>
      </table>

      {reservation.status === 'RESERVED' && (
        <>
          <h2>픽업 QR</h2>
          <PickupQr reservationId={reservation.reservationId} />

          <h2>취소</h2>
          {/* 취소 가능 여부는 서버가 계산한 cancelable 을 따른다. 시각이 지나는 사이 누르면 서버가 422 로 막는다 */}
          {reservation.cancelable ? (
            <>
              <p className="muted">{formatDateTime(reservation.cancelDeadline)}까지 취소할 수 있습니다.</p>
              {cancel.error && <p className="notice error">{messageOf(cancel.error)}</p>}
              <button className="button secondary" disabled={cancel.isPending} onClick={onCancel}>
                {cancel.isPending ? '취소하는 중…' : '예약 취소'}
              </button>
            </>
          ) : (
            <p className="notice warning">취소 마감 시각({formatDateTime(reservation.cancelDeadline)})이 지나 취소할 수 없습니다.</p>
          )}
        </>
      )}
    </>
  )
}

/** C-07: 서버가 준 서명 토큰(code)을 QR 이미지로. 사장님이 이 QR 을 읽어 수령 처리한다 */
function PickupQr({ reservationId }: { reservationId: number }) {
  const [shown, setShown] = useState(false)
  const pickupCode = useQuery({
    queryKey: ['pickup-code', reservationId],
    queryFn: () => api<PickupCode>(`/api/v1/reservations/${reservationId}/pickup-code`),
    enabled: shown,
    staleTime: 0,
    gcTime: 0,   // 토큰은 캐시에 남기지 않는다
  })
  const [image, setImage] = useState<string | null>(null)

  useEffect(() => {
    if (!pickupCode.data) return
    let canceled = false
    QRCode.toDataURL(pickupCode.data.code, { width: 240, margin: 1 }).then((url) => {
      if (!canceled) setImage(url)
    })
    return () => {
      canceled = true
    }
  }, [pickupCode.data])

  if (!shown) {
    return <button className="button" onClick={() => setShown(true)}>QR 보기</button>
  }
  if (pickupCode.isLoading) return <p className="muted">QR 을 만드는 중…</p>
  if (pickupCode.error) return <p className="notice error">{messageOf(pickupCode.error)}</p>
  if (!pickupCode.data) return null

  return (
    <div className={s.qr}>
      {image && <img src={image} alt="픽업 QR 코드" width={240} height={240} />}
      <p className="muted">{formatDateTime(pickupCode.data.expiresAt)}까지 유효합니다. 매장에서 보여 주세요.</p>
      <button className="button secondary" onClick={() => pickupCode.refetch()}>새로 받기</button>
    </div>
  )
}
