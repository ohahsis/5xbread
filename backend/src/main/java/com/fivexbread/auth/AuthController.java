package com.fivexbread.auth;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/tokens")
@RequiredArgsConstructor
public class AuthController {

    private final AuthFacade authFacade;

    /** 로그인 = 토큰 자원 생성 (5장 인증 API). 401 번호·비밀번호 불일치, 423 잠김 */
    @PostMapping
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authFacade.login(request));
    }
}
