package com.fivexbread.realtime;

import com.fivexbread.auth.AuthMember;
import com.fivexbread.auth.InvalidTokenException;
import com.fivexbread.auth.JwtProvider;
import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import com.fivexbread.member.Role;
import java.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * STOMP 인증·권한 (13-3 1단계). 브라우저 WebSocket 은 HTTP 헤더를 넣을 수 없어 CONNECT 프레임의 Authorization 헤더로 토큰을 받는다.
 * - CONNECT: 토큰이 없으면 익명. 잔여 토픽은 공개 정보라(빵 목록과 같은 공개 범위) 로그인 없이도 구독한다.
 *   토큰이 만료·위조됐으면 HTTP(JwtAuthenticationFilter)와 같이 익명으로 둔다. 잔여 구독은 그대로 되고, 사장님 토픽은 SUBSCRIBE 에서 막힌다.
 *   비밀번호 변경이 필요한 토큰(pcr)은 HTTP 의 공개 경로와 같이 거절한다 (decisions.md 6장 "AU-06·AU-08 변경 강제")
 * - SUBSCRIBE: 허용 목록. /topic/sale-dates/{yyyy-MM-dd}/stock 은 누구나, /topic/admin/** 은 사장님만, 그 밖은 거절.
 *   목적지 패턴(*, **)을 쓴 구독도 날짜가 아니라서 거절된다 (Simple Broker 는 구독 목적지를 Ant 패턴으로 비교한다)
 * - SEND: 거절. 받으면 Simple Broker 가 /topic 구독자 모두에게 그대로 전달해 손님이 가짜 잔여를 뿌릴 수 있다
 * 거절은 BusinessException 으로 던지고 StompErrorHandler 가 ERROR 프레임(message = 오류 코드)으로 바꾼다. 토큰은 로그에 남기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthInterceptor implements ChannelInterceptor {

    static final String STOCK_TOPIC_PREFIX = "/topic/sale-dates/";
    private static final Pattern STOCK_TOPIC = Pattern.compile("^/topic/sale-dates/(\\d{4}-\\d{2}-\\d{2})/stock$");
    private static final String ADMIN_TOPIC_PREFIX = "/topic/admin/";
    private static final String BEARER = "Bearer ";
    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtProvider jwtProvider;

    public static String stockTopic(LocalDate saleDate) {
        return STOCK_TOPIC_PREFIX + saleDate + "/stock";
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;                                     // heartbeat 등
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
            authenticate(accessor);
        } else if (command == StompCommand.SUBSCRIBE) {
            authorize(accessor.getDestination(), accessor.getUser());
        } else if (command == StompCommand.SEND) {
            throw new BusinessException(ErrorCode.METHOD_NOT_ALLOWED);
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return;                                             // 익명
        }
        AuthMember member;
        try {
            member = jwtProvider.parse(header.substring(BEARER.length()).trim());
        } catch (InvalidTokenException e) {
            if (e.getReason() == InvalidTokenException.Reason.EXPIRED) {
                log.debug("STOMP CONNECT 만료된 Access Token. 익명으로 연결");
            } else {
                log.warn("STOMP CONNECT 잘못된 Access Token reason={}. 익명으로 연결", e.getReason());
            }
            return;
        }
        if (member.passwordChangeRequired()) {
            throw new BusinessException(ErrorCode.PASSWORD_CHANGE_REQUIRED);
        }
        var authorities = List.of(new SimpleGrantedAuthority(ROLE_PREFIX + member.role().name()));
        accessor.setUser(UsernamePasswordAuthenticationToken.authenticated(member, null, authorities));
    }

    private static void authorize(String destination, Principal user) {
        if (destination == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        Matcher stock = STOCK_TOPIC.matcher(destination);
        if (stock.matches()) {
            parseDate(stock.group(1));
            return;
        }
        if (destination.startsWith(ADMIN_TOPIC_PREFIX)) {
            if (!isAdmin(user)) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
            return;
        }
        throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }

    private static void parseDate(String value) {
        try {
            LocalDate.parse(value);                             // 2026-02-30 같은 값
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private static boolean isAdmin(Principal user) {
        return user instanceof UsernamePasswordAuthenticationToken token
                && token.getPrincipal() instanceof AuthMember member
                && member.role() == Role.ADMIN;
    }
}
