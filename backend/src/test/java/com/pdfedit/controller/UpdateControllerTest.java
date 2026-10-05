package com.pdfedit.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.HttpStatus;

import com.pdfedit.service.UpdateService;

class UpdateControllerTest {

    /** Updating switched off, so nothing here can reach the network or start anything. */
    private final UpdateController controller = new UpdateController(
            new UpdateService(false, "0.051", "me/app", "http://127.0.0.1:1", "", new GenericApplicationContext()));

    @Test
    void anInstallRequestWithoutTheAppsOwnHeaderIsRefused() {
        assertThat(controller.install(null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.install("something else").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void whenSwitchedOffTheCheckSaysSo() {
        assertThat(controller.check(false).enabled()).isFalse();
    }
}
