package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Stamps are registered at a seal's size, remember where they went, and outlast a restart. */
class StampStoreTest {

    private static byte[] png(int w, int h) throws Exception {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillOval(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void aStampIsKeptWithItsSizeAndPlaceAcrossARestart(@TempDir Path folder) throws Exception {
        StampStore store = new StampStore(folder);
        StampStore.Stamp seal = store.add("회사 직인", png(400, 400));
        StampStore.Stamp sign = store.add(null, png(600, 200));
        assertThat(seal.width()).isEqualTo(70.0);
        assertThat(sign.width()).isEqualTo(70.0);
        assertThat(sign.height()).isCloseTo(23.3, org.assertj.core.data.Offset.offset(0.1));
        assertThat(sign.name()).isEqualTo("도장 2");

        store.update(seal.id(), null, 60.0, 60.0, 0.72, 0.85);

        StampStore again = new StampStore(folder);
        assertThat(again.list()).extracting(StampStore.Stamp::name).containsExactly("회사 직인", "도장 2");
        StampStore.Stamp kept = again.list().get(0);
        assertThat(kept.width()).isEqualTo(60.0);
        assertThat(kept.lastX()).isEqualTo(0.72);
        assertThat(again.picture(kept.id())).isNotEmpty();

        again.delete(kept.id());
        assertThat(new StampStore(folder).list()).hasSize(1);
    }

    @Test
    void notAPictureIsRefused(@TempDir Path folder) {
        assertThatThrownBy(() -> new StampStore(folder).add("x", new byte[] {1, 2, 3}))
                .isInstanceOf(InvalidEditException.class).hasMessageContaining("그림 파일");
    }
}
