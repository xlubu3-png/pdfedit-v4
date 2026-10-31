package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import org.junit.jupiter.api.Test;

/** A picture is refused for its size before it is decoded, not after: decoding is what fills the memory. */
class ImageLimitsTest {

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(name);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    /** A PNG that says it is width x height RGB pixels; its picture data is a few zero bytes, all black. */
    private static byte[] png(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        chunk(out, "IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) 8).put((byte) 2)
                .put((byte) 0).put((byte) 0).put((byte) 0).array());
        Deflater deflater = new Deflater();
        // Enough rows for a small picture (a filter byte plus three bytes a pixel each); a huge one is cut short.
        deflater.setInput(new byte[(int) Math.min(64, (1 + width * 3L) * height)]);
        deflater.finish();
        byte[] buffer = new byte[256];
        byte[] idat = java.util.Arrays.copyOf(buffer, deflater.deflate(buffer));
        chunk(out, "IDAT", idat);
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    @Test
    void aSmallFileThatUnpacksIntoAHugePictureIsRefusedWithoutDecodingIt() {
        byte[] bomb = png(30_000, 30_000);
        assertThat(bomb.length).isLessThan(1_000);

        long start = System.nanoTime();
        assertThatThrownBy(() -> ImageDrawing.read(bomb))
                .isInstanceOf(InvalidEditException.class)
                .hasMessageContaining("너무 큽니다");
        assertThat((System.nanoTime() - start) / 1_000_000).as("refused from the header, not decoded").isLessThan(2_000);
    }

    @Test
    void aPictureOfAReasonableSizeIsStillRead() throws IOException {
        ImageDrawing.Upload upload = ImageDrawing.read(png(2, 2));

        assertThat(upload.width()).isEqualTo(2);
        assertThat(upload.height()).isEqualTo(2);
    }
}
