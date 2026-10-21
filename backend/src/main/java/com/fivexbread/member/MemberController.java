package com.fivexbread.member;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberFacade memberFacade;

    /**
     * 가입 (C-01). 클라이언트 IP는 server.forward-headers-strategy=native로
     * Nginx가 붙인 X-Forwarded-For를 Tomcat이 반영한 값 (13-5).
     */
    @PostMapping
    public ResponseEntity<SignupResponse> signUp(@Valid @RequestBody SignupRequest request,
                                                 HttpServletRequest httpRequest) {
        SignupResponse response = memberFacade.signUp(request, httpRequest.getRemoteAddr());
        return ResponseEntity.created(URI.create("/api/v1/members/" + response.memberId())).body(response);
    }
}
