package com.fivexbread.realtime;

import java.util.List;

/**
 * /topic/sale-dates/{date}/stock 으로 보내는 메시지 (13-3 "API·메시지"). 바뀐 빵만, 잔여는 변화량이 아니라 절대값이다.
 * 메시지가 빠지거나 순서가 바뀌어도 다음 메시지·재연결 스냅샷이 맞춘다.
 *
 * @param version 이 묶음에 들어간 변경 중 가장 큰 판매일 버전. 화면은 목록(스냅샷)·이미 적용한 메시지보다 작거나 같으면 버린다
 */
public record StockMessage(long version, List<Change> changes) {

    public record Change(long breadId, int remaining) {
    }
}
