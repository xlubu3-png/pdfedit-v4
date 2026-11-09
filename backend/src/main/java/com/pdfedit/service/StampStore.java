package com.pdfedit.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

/**
 * 도장·서명: pictures of the company seal or a signature the user registers once and stamps onto documents. Kept in
 * the data folder (stamps/), with their name, the size they are stamped at and where on the page they went last, so
 * the next document gets them the same way. Without a data folder (tests) they are kept for the run only.
 */
@Service
public class StampStore {

    private static final Logger log = LoggerFactory.getLogger(StampStore.class);
    /** Most stamps kept, and the biggest picture (bytes). */
    public static final int MAX_STAMPS = 30;
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    /** A stamp's first size: its longer side this many points (about 2.5 cm, a seal's size). */
    private static final double FIRST_SIZE = 70;

    /**
     * A stamp: its size (points) when stamped, and where it went last as shares of the page (0 - 1 from the
     * top-left of the page to the stamp's top-left), null before it is first stamped.
     */
    public record Stamp(String id, String name, double width, double height, Double lastX, Double lastY) {
    }

    private final Path dir;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Map<String, Stamp> stamps = new ConcurrentHashMap<>();
    private final Map<String, byte[]> inMemory = new ConcurrentHashMap<>();
    /** The order they were registered in. */
    private final List<String> order = new ArrayList<>();

    @org.springframework.beans.factory.annotation.Autowired
    public StampStore(@Value("${app.data-dir}") String dataDir) {
        this(dataDir == null || dataDir.isBlank() ? null : Path.of(dataDir).resolve("stamps"));
    }

    public StampStore(Path dir) {
        this.dir = dir;
        if (dir != null) {
            try {
                Files.createDirectories(dir);
                Path list = dir.resolve("stamps.json");
                if (Files.exists(list)) {
                    for (Stamp s : mapper.readValue(Files.readAllBytes(list), Stamp[].class)) {
                        if (Files.exists(dir.resolve(s.id() + ".img"))) {
                            stamps.put(s.id(), s);
                            order.add(s.id());
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                log.warn("Could not read the stamps in {}: {}", dir, e.toString());
            }
        }
    }

    public synchronized List<Stamp> list() {
        return order.stream().map(stamps::get).toList();
    }

    /** Registers a picture as a stamp, at a seal's size with its own shape. */
    public synchronized Stamp add(String name, byte[] picture) {
        if (stamps.size() >= MAX_STAMPS) {
            throw new InvalidEditException("도장·서명은 " + MAX_STAMPS + "개까지 등록할 수 있습니다. 쓰지 않는 것을 지워 주세요.");
        }
        if (picture.length > MAX_BYTES) {
            throw new InvalidEditException("그림 파일이 너무 큽니다 (5MB까지).");
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(picture));
        } catch (IOException e) {
            image = null;
        }
        if (image == null || image.getWidth() == 0 || image.getHeight() == 0) {
            throw new InvalidEditException("그림 파일(PNG·JPG)이 아니거나 읽을 수 없습니다.");
        }
        double longer = Math.max(image.getWidth(), image.getHeight());
        double width = Math.round(FIRST_SIZE * image.getWidth() / longer * 10) / 10.0;
        double height = Math.round(FIRST_SIZE * image.getHeight() / longer * 10) / 10.0;
        String clean = name == null || name.isBlank() ? "도장 " + (stamps.size() + 1) : name.strip();
        Stamp stamp = new Stamp(UUID.randomUUID().toString(), clean.length() > 40 ? clean.substring(0, 40) : clean,
                width, height, null, null);
        write(stamp.id(), picture);
        stamps.put(stamp.id(), stamp);
        order.add(stamp.id());
        save();
        return stamp;
    }

    /** Its picture, or null when there is no such stamp. */
    public byte[] picture(String id) {
        if (!stamps.containsKey(id)) {
            return null;
        }
        if (dir == null) {
            return inMemory.get(id);
        }
        try {
            return Files.readAllBytes(dir.resolve(id + ".img"));
        } catch (IOException e) {
            return null;
        }
    }

    /** Changes its name, its size, or where it went last (what is null is left as it is). */
    public synchronized Stamp update(String id, String name, Double width, Double height, Double lastX, Double lastY) {
        Stamp s = stamps.get(id);
        if (s == null) {
            throw new InvalidEditException("없는 도장입니다.");
        }
        double w = width == null ? s.width() : width;
        double h = height == null ? s.height() : height;
        if (!(w >= 4 && w <= 2000 && h >= 4 && h <= 2000)) {
            throw new InvalidEditException("도장 크기가 잘못되었습니다.");
        }
        Double x = lastX == null ? s.lastX() : clampShare(lastX);
        Double y = lastY == null ? s.lastY() : clampShare(lastY);
        String n = name == null || name.isBlank() ? s.name() : name.strip();
        Stamp updated = new Stamp(id, n.length() > 40 ? n.substring(0, 40) : n, w, h, x, y);
        stamps.put(id, updated);
        save();
        return updated;
    }

    public synchronized void delete(String id) {
        if (stamps.remove(id) == null) {
            return;
        }
        order.remove(id);
        inMemory.remove(id);
        if (dir != null) {
            try {
                Files.deleteIfExists(dir.resolve(id + ".img"));
            } catch (IOException e) {
                log.warn("Could not delete the stamp picture {}: {}", id, e.toString());
            }
        }
        save();
    }

    private static double clampShare(double v) {
        if (!Double.isFinite(v)) {
            throw new InvalidEditException("도장 자리가 잘못되었습니다.");
        }
        return Math.max(0, Math.min(1, v));
    }

    private void write(String id, byte[] picture) {
        if (dir == null) {
            inMemory.put(id, picture);
            return;
        }
        try {
            Files.write(dir.resolve(id + ".img"), picture);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot save the stamp", e);
        }
    }

    private void save() {
        if (dir == null) {
            return;
        }
        Path list = dir.resolve("stamps.json");
        Path temp = dir.resolve("stamps.json.tmp");
        try {
            Files.write(temp, mapper.writeValueAsBytes(list()));
            Files.move(temp, list, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Could not save the stamps: {}", e.toString());
        }
    }
}
