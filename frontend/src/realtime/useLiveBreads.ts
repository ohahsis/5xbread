// 판매일 빵 목록(C-02) + 실시간 잔여(C-03).
//
// 순서:
//   1. STOMP 연결 → StockSync 버퍼링 시작 → /topic/sale-dates/{date}/stock 구독
//   2. 구독을 보낸 뒤에 목록 조회 (useQuery 는 그 전까지 enabled: false)
//   3. 목록이 오면 StockSync.onSnapshot → 버퍼 중 목록보다 새로운 메시지만 적용
//   4. 연결이 끊겼다 다시 붙으면(onConnect 가 다시 불림) 1·2 를 반복해 목록을 다시 받는다
//
// SUBSCRIBE 에 receipt 를 붙여 "등록 확인 뒤 조회"로 하지 않은 이유: 서버의 Simple Broker 는 SUBSCRIBE receipt 를
// 돌려주지 않는다(Spring 은 DISCONNECT receipt 만 보냄). 구독 등록 전에 생긴 변경은 서버의 200ms 묶음이 메워 준다.
// decisions.md 6장 "C-03 화면 규칙" 참고.
//
// 연결은 익명이다(토큰을 보내지 않음). 잔여 토픽은 공개라 로그인이 필요 없고, 토큰을 보내면 만료·임시 비밀번호 토큰 처리만 늘어난다.
import { Client } from '@stomp/stompjs'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useReducer, useRef, useState } from 'react'
import { api } from '../api/client'
import type { SaleBread, SaleBreads } from '../api/types'
import { StockSync, type StockMessage } from './stockSync'

/** 이 시간 안에 연결되지 않으면 실시간 없이 목록만 먼저 보여 준다 */
const CONNECT_WAIT_MS = 3000

export function breadsQueryKey(date: string) {
  return ['sale-breads', date] as const
}

export type LiveBreads = {
  saleBreads: SaleBreads | undefined
  breads: SaleBread[]
  /** 실시간 연결 상태 (화면 표시용) */
  live: boolean
  isLoading: boolean
  error: unknown
}

export function useLiveBreads(date: string): LiveBreads {
  const queryClient = useQueryClient()
  const syncRef = useRef(new StockSync())
  const [, rerender] = useReducer((count: number) => count + 1, 0)
  const [live, setLive] = useState(false)
  /** 목록 조회를 시작해도 되는 판매일. 구독을 보냈거나, 연결을 기다리다 포기한 날짜 */
  const [readyDate, setReadyDate] = useState<string | null>(null)

  useEffect(() => {
    const sync = new StockSync()
    syncRef.current = sync

    const client = new Client({
      brokerURL: webSocketUrl('/ws'),
      heartbeatIncoming: 10_000,     // 서버와 같은 10초 (decisions.md 6장 "C-03 연결 유지")
      heartbeatOutgoing: 10_000,
      reconnectDelay: 3_000,
    })

    client.onConnect = () => {
      sync.startBuffering()
      client.subscribe(`/topic/sale-dates/${date}/stock`, (frame) => {
        const message: StockMessage = JSON.parse(frame.body)
        if (sync.onMessage(message)) rerender()
      })
      setLive(true)
      setReadyDate(date)
      // 재연결이면 목록을 다시 받는다. 진행 중인 조회(구독 전에 보낸 것)는 취소하고 새로 보낸다(cancelRefetch 기본 true).
      // 처음 연결이면 아직 enabled: false 라 아무 일도 없고, 위 setReadyDate 가 조회를 시작한다.
      void queryClient.refetchQueries({ queryKey: breadsQueryKey(date) })
    }
    client.onWebSocketClose = () => setLive(false)

    client.activate()
    const giveUp = setTimeout(() => setReadyDate(date), CONNECT_WAIT_MS)

    return () => {
      clearTimeout(giveUp)
      setLive(false)
      void client.deactivate()
    }
  }, [date, queryClient])

  const query = useQuery({
    queryKey: breadsQueryKey(date),
    queryFn: () => api<SaleBreads>(`/api/v1/sale-dates/${date}/breads`),
    enabled: readyDate === date,
    // 다시 조회는 구독 직후·재연결·예약/취소 뒤에만. 창 포커스·네트워크 복구 때는 STOMP 재연결이 다시 조회한다.
    // staleTime 은 기본(0)으로 둔다: 전에 본 날짜로 돌아왔을 때 캐시가 fresh 면 enabled 가 켜져도 조회하지 않아,
    // 구독 뒤 목록 조회가 빠지기 때문
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })

  // 목록을 새로 받을 때마다(dataUpdatedAt) 스냅샷으로 넘긴다. 내용이 같아도 시각은 바뀌므로 매번 실행된다
  useEffect(() => {
    if (!query.data) return
    syncRef.current.onSnapshot(query.data.version, query.data.breads)
    rerender()
  }, [query.data, query.dataUpdatedAt])

  const saleBreads = query.data
  const breads = saleBreads ? saleBreads.breads.map((bread) => withLiveRemaining(bread, saleBreads, syncRef.current)) : []

  return { saleBreads, breads, live, isLoading: query.isLoading, error: query.error }
}

/** 잔여를 실시간 값으로 바꾸고, 판매일이 OPEN 이면 상태(AVAILABLE/SOLD_OUT)도 잔여로 다시 정한다. 순서는 목록 그대로 둔다(줄이 움직이지 않게) */
function withLiveRemaining(bread: SaleBread, saleBreads: SaleBreads, sync: StockSync): SaleBread {
  const remaining = sync.remainingOf(bread.breadId) ?? bread.remaining
  if (saleBreads.status !== 'OPEN') return { ...bread, remaining }
  return { ...bread, remaining, status: remaining > 0 ? 'AVAILABLE' : 'SOLD_OUT' }
}

function webSocketUrl(path: string): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}${path}`
}
