package com.pdfedit.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pdfedit.dto.UpdateInfo;
import com.pdfedit.service.UpdateService;

@RestController
@RequestMapping("/api/v1/pdf/update")
public class UpdateController {

    /**
     * Sent by this app's own page. A header like this cannot be added by another website's form or plain
     * request without the browser asking this server first, so other pages cannot start an update.
     */
    static final String APP_HEADER = "X-Requested-With";
    static final String APP_HEADER_VALUE = "WINTECH_PDF";

    private final UpdateService updates;

    public UpdateController(UpdateService updates) {
        this.updates = updates;
    }

    /** Whether a newer version exists. {@code refresh} asks GitHub again instead of using the answer from a few hours ago. */
    @GetMapping
    public UpdateInfo check(@RequestParam(defaultValue = "false") boolean refresh) {
        return updates.check(refresh);
    }

    /** Downloads and starts the installer of the newest version; the app quits a moment after answering. */
    @PostMapping("/install")
    public ResponseEntity<String> install(@RequestHeader(value = APP_HEADER, required = false) String marker) {
        if (!APP_HEADER_VALUE.equals(marker)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("이 화면에서만 업데이트를 시작할 수 있습니다.");
        }
        updates.install();
        return ResponseEntity.accepted().body("설치 프로그램을 실행했습니다. 앱이 곧 종료됩니다.");
    }
}
