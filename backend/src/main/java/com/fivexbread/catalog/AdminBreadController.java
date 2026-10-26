package com.fivexbread.catalog;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 사장님 빵 관리 (A-01, 10장 관리 API). ADMIN 만(SecurityConfig). DELETE 는 두지 않아 405 */
@RestController
@RequestMapping("/api/v1/admin/breads")
@RequiredArgsConstructor
public class AdminBreadController {

    private final BreadService breadService;

    @GetMapping
    public List<BreadResponse> list(@RequestParam(required = false) Boolean active) {
        return breadService.list(active).stream().map(BreadResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<BreadResponse> register(@Valid @RequestBody BreadCreateRequest request) {
        BreadResponse response = BreadResponse.from(breadService.register(request));
        return ResponseEntity.created(URI.create("/api/v1/admin/breads/" + response.id())).body(response);
    }

    @PatchMapping("/{breadId}")
    public BreadResponse update(@PathVariable long breadId, @Valid @RequestBody BreadUpdateRequest request) {
        return BreadResponse.from(breadService.update(breadId, request));
    }
}
