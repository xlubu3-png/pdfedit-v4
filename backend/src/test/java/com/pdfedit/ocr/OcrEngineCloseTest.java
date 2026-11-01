package com.pdfedit.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** Closing the engine waits for a reading under way: closed under it, the native engine could crash the app. */
class OcrEngineCloseTest {

    @Test
    void closingWaitsWhileAReadingHoldsTheEngine() throws Exception {
        OcrEngine engine = OcrEngine.load();
        Thread closing;
        // A reading holds the engine (read() is synchronized on it) while the app is told to quit.
        synchronized (engine) {
            closing = Thread.ofPlatform().start(() -> {
                try {
                    engine.close();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            closing.join(TimeUnit.MILLISECONDS.toMillis(300));
            assertThat(closing.isAlive()).as("close() waits for the reading").isTrue();
        }
        closing.join(TimeUnit.SECONDS.toMillis(10));
        assertThat(closing.isAlive()).as("and closes once it is over").isFalse();
    }
}
