package com.fivexbread.realtime;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * 클라이언트 프레임 처리 중 예외를 ERROR 프레임으로 (13-3 어필 "임의 토픽 구독 시 ERROR 프레임").
 * message 헤더에 오류 코드(FORBIDDEN 등), 본문에 HTTP 응답과 같은 문구. 그 밖의 예외는 내용을 숨기고 INTERNAL_ERROR.
 * STOMP 규약대로 ERROR 뒤에 서버가 연결을 닫는다.
 */
@Component
public class StompErrorHandler extends StompSubProtocolErrorHandler {

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
        ErrorCode code = errorCode(ex);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(code.name());
        accessor.setLeaveMutable(true);
        if (clientMessage != null) {
            StompHeaderAccessor client = StompHeaderAccessor.wrap(clientMessage);
            if (client.getReceipt() != null) {
                accessor.setReceiptId(client.getReceipt());
            }
        }
        MessageHeaders headers = accessor.getMessageHeaders();
        return MessageBuilder.createMessage(code.getMessage().getBytes(StandardCharsets.UTF_8), headers);
    }

    private static ErrorCode errorCode(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof BusinessException business) {
                return business.getErrorCode();
            }
        }
        return ErrorCode.INTERNAL_ERROR;
    }
}
