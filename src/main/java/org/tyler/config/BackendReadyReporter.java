package org.tyler.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Reports the assigned HTTP port to the process that started the desktop backend. */
@Component
public class BackendReadyReporter {

    @EventListener(ApplicationReadyEvent.class)
    public void reportReady(ApplicationReadyEvent event) {
        String nonce = System.getenv("TYLER_STARTUP_NONCE");
        if (nonce == null || !nonce.matches("[0-9a-f]{32}")) {
            return;
        }
        ServletWebServerApplicationContext context =
                (ServletWebServerApplicationContext) event.getApplicationContext();
        int port = context.getWebServer().getPort();
        System.out.println("TYLER_READY " + nonce + " " + port);
        System.out.flush();
    }
}
