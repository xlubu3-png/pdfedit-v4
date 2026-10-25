package com.pdfedit.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.pdfedit.redact.Redaction;
import com.pdfedit.text.AddedImage;
import com.pdfedit.text.AddedText;
import com.pdfedit.text.RunEdit;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds the uploaded PDFs and the edits made to them, keyed by a generated document id, so later
 * requests (preview, text editing, export) can refer to a document without re-uploading it.
 *
 * <p>With a data folder, everything is also kept on disk ({@code <id>.pdf} plus {@code <id>.json} for
 * the file name and the edits), so documents survive a restart of the app and a reload of the
 * browser. Without one (tests), it lives in memory only. Documents that nobody touched for the
 * retention period are deleted.
 */
@Service
public class PdfDocumentStore {

    private static final Logger log = LoggerFactory.getLogger(PdfDocumentStore.class);
    private static final Duration TOUCH_INTERVAL = Duration.ofHours(1);
    private static final Duration CONTENT_IDLE = Duration.ofMinutes(10);

    private final Map<String, Entry> documents = new ConcurrentHashMap<>();
    private final Path dataDir;
    private final Duration retention;
    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public record DocumentInfo(String fileName, int pageCount) {
    }

    /** What is written next to the PDF; page and run numbers are strings because JSON keys are. */
    record Stored(String fileName, int pageCount, Map<String, Map<String, RunEdit>> edits,
                  Map<String, List<AddedText>> added, Map<String, List<AddedImage>> images,
                  Map<String, List<Redaction>> redactions) {
    }

    /** Most pictures uploaded for one document, and the biggest one (bytes). */
    public static final int MAX_IMAGES = 50;
    public static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    /** In memory only. */
    public PdfDocumentStore() {
        this(null, Duration.ofMinutes(30));
    }

    @Autowired
    public PdfDocumentStore(@Value("${app.data-dir}") String dataDir, @Value("${app.retention-days:7}") int days) {
        this(dataDir == null || dataDir.isBlank() ? null : Path.of(dataDir), Duration.ofDays(days));
    }

    public PdfDocumentStore(Path dataDir, Duration retention) {
        this.dataDir = dataDir;
        this.retention = retention;
        if (dataDir != null) {
            try {
                Files.createDirectories(dataDir);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot create the data folder " + dataDir, e);
            }
            loadAll();
        }
    }

    public String store(String fileName, byte[] content, int pageCount) {
        String documentId = UUID.randomUUID().toString();
        Entry entry = new Entry(fileName, content, pageCount);
        documents.put(documentId, entry);
        if (dataDir != null) {
            try {
                Files.write(pdfPath(documentId), content);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot save " + fileName, e);
            }
            persist(documentId);
        }
        return documentId;
    }

    public byte[] getContent(String documentId) {
        Entry entry = require(documentId);
        byte[] content = entry.content;
        if (content == null) {
            try {
                content = Files.readAllBytes(pdfPath(documentId));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read the saved document " + documentId, e);
            }
            entry.content = content;
        }
        return content;
    }

    public DocumentInfo getInfo(String documentId) {
        Entry entry = require(documentId);
        return new DocumentInfo(entry.fileName, entry.pageCount);
    }

    /**
     * Live, mutable text edits (run index to edit) of one page; created empty on first use. Whoever
     * changes the map should call {@link #persist} afterwards.
     */
    public Map<Integer, RunEdit> pageEdits(String documentId, int pageIndex) {
        return require(documentId).editsByPage.computeIfAbsent(pageIndex, k -> new ConcurrentHashMap<>());
    }

    /** Replaces the text boxes the user added to a page. */
    public void setAddedTexts(String documentId, int pageIndex, List<AddedText> added) {
        Entry entry = require(documentId);
        if (added.isEmpty()) {
            entry.addedByPage.remove(pageIndex);
        } else {
            entry.addedByPage.put(pageIndex, List.copyOf(added));
        }
        persist(documentId);
    }

    /** Replaces the picture boxes the user added to a page. */
    public void setAddedImages(String documentId, int pageIndex, List<AddedImage> images) {
        Entry entry = require(documentId);
        if (images.isEmpty()) {
            entry.imagesByPage.remove(pageIndex);
        } else {
            entry.imagesByPage.put(pageIndex, List.copyOf(images));
        }
        persist(documentId);
    }

    /** Replaces the redaction areas of a page. */
    public void setRedactions(String documentId, int pageIndex, List<Redaction> areas) {
        Entry entry = require(documentId);
        if (areas.isEmpty()) {
            entry.redactionsByPage.remove(pageIndex);
        } else {
            entry.redactionsByPage.put(pageIndex, List.copyOf(areas));
        }
        persist(documentId);
    }

    /** The redaction areas of a page; empty when it has none. */
    public List<Redaction> redactions(String documentId, int pageIndex) {
        return require(documentId).redactionsByPage.getOrDefault(pageIndex, List.of());
    }

    /**
     * Keeps an uploaded picture for the document and returns the id boxes refer to it by.
     *
     * @throws InvalidEditException when the document already has as many pictures as it may
     */
    public String storeImage(String documentId, byte[] data) {
        Entry entry = require(documentId);
        synchronized (entry) {
            if (entry.imageIds.size() >= MAX_IMAGES) {
                throw new InvalidEditException("한 문서에는 그림을 " + MAX_IMAGES + "개까지 올릴 수 있습니다.");
            }
            String imageId = UUID.randomUUID().toString();
            if (dataDir != null) {
                try {
                    Files.write(imagePath(documentId, imageId), data);
                } catch (IOException e) {
                    throw new UncheckedIOException("Cannot save the picture", e);
                }
            } else {
                entry.imagesInMemory.put(imageId, data);
            }
            entry.imageIds.add(imageId);
            return imageId;
        }
    }

    /** The bytes of a picture uploaded for the document, or null if there is no such picture. */
    public byte[] imageBytes(String documentId, String imageId) {
        Entry entry = require(documentId);
        if (!entry.imageIds.contains(imageId)) {
            return null;
        }
        byte[] inMemory = entry.imagesInMemory.get(imageId);
        if (inMemory != null || dataDir == null) {
            return inMemory;
        }
        try {
            return Files.readAllBytes(imagePath(documentId, imageId));
        } catch (IOException e) {
            return null;
        }
    }

    /** A copy of everything edited on the page, safe to use while other requests keep editing. */
    public PageEdits pageEditsSnapshot(String documentId, int pageIndex) {
        Entry entry = require(documentId);
        Map<Integer, RunEdit> runs = entry.editsByPage.get(pageIndex);
        List<PageEdits.PlacedImage> images = new java.util.ArrayList<>();
        for (AddedImage box : entry.imagesByPage.getOrDefault(pageIndex, List.of())) {
            byte[] data = imageBytes(documentId, box.imageId());
            if (data != null) {
                images.add(new PageEdits.PlacedImage(box, data));
            }
        }
        return new PageEdits(runs == null ? Map.of() : new HashMap<>(runs),
                entry.addedByPage.getOrDefault(pageIndex, List.of()), images);
    }

    public void clearPageEdits(String documentId, int pageIndex) {
        Entry entry = require(documentId);
        entry.editsByPage.remove(pageIndex);
        entry.addedByPage.remove(pageIndex);
        entry.imagesByPage.remove(pageIndex);
        persist(documentId);
    }

    /** Writes the file name and edits of a document to disk (a no-op without a data folder). */
    public void persist(String documentId) {
        Entry entry = documents.get(documentId);
        if (dataDir == null || entry == null) {
            return;
        }
        Map<String, Map<String, RunEdit>> edits = new LinkedHashMap<>();
        entry.editsByPage.forEach((page, runs) -> {
            if (!runs.isEmpty()) {
                Map<String, RunEdit> byRun = new LinkedHashMap<>();
                runs.forEach((run, edit) -> byRun.put(String.valueOf(run), edit));
                edits.put(String.valueOf(page), byRun);
            }
        });
        Map<String, List<AddedText>> added = new LinkedHashMap<>();
        entry.addedByPage.forEach((page, boxes) -> added.put(String.valueOf(page), boxes));
        Map<String, List<AddedImage>> images = new LinkedHashMap<>();
        entry.imagesByPage.forEach((page, boxes) -> images.put(String.valueOf(page), boxes));
        Map<String, List<Redaction>> redactions = new LinkedHashMap<>();
        entry.redactionsByPage.forEach((page, areas) -> redactions.put(String.valueOf(page), areas));
        synchronized (entry) {
            Path target = metaPath(documentId);
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            try {
                Files.write(temp, mapper.writeValueAsBytes(
                        new Stored(entry.fileName, entry.pageCount, edits, added, images, redactions)));
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                log.warn("Could not save the edits of {}: {}", documentId, e.toString());
            }
        }
    }

    private Entry require(String documentId) {
        Entry entry = documents.get(documentId);
        if (entry == null) {
            throw new NoSuchElementException("Unknown document id: " + documentId);
        }
        Instant now = Instant.now();
        if (dataDir != null && Duration.between(entry.lastAccessed, now).compareTo(TOUCH_INTERVAL) > 0) {
            // Keeps the file's date in step with use, so a document in daily use is never expired.
            try {
                Files.setLastModifiedTime(metaPath(documentId), FileTime.from(now));
            } catch (IOException ignored) {
                // only affects when the document expires
            }
        }
        entry.lastAccessed = now;
        return entry;
    }

    /** Forgets a document and removes its saved files at once; an unknown id is not an error. */
    public void delete(String documentId) {
        if (documents.remove(documentId) != null && dataDir != null) {
            deleteFiles(documentId);
        }
    }

    /**
     * Lets go of the in-memory copy of documents nobody used for a while. The file on disk stays and
     * is read again on the next use, so big PDFs opened earlier don't fill the heap until they expire.
     * Without a data folder the memory is the only copy, so nothing is released.
     */
    void releaseIdleContent(Duration idle) {
        if (dataDir == null) {
            return;
        }
        Instant cutoff = Instant.now().minus(idle);
        documents.values().forEach(entry -> {
            if (entry.content != null && !entry.lastAccessed.isAfter(cutoff)) {
                entry.content = null;
            }
        });
    }

    @Scheduled(fixedRate = 10 * 60 * 1000L)
    void evictExpired() {
        releaseIdleContent(CONTENT_IDLE);
        Instant cutoff = Instant.now().minus(retention);
        documents.entrySet().removeIf(e -> {
            boolean expired = e.getValue().lastAccessed.isBefore(cutoff);
            if (expired && dataDir != null) {
                deleteFiles(e.getKey());
            }
            return expired;
        });
    }

    private void loadAll() {
        try (Stream<Path> files = Files.list(dataDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(this::loadOne);
        } catch (IOException e) {
            log.warn("Could not read the saved documents in {}: {}", dataDir, e.toString());
        }
        log.info("{} saved document(s) found in {}", documents.size(), dataDir);
    }

    private void loadOne(Path meta) {
        String name = meta.getFileName().toString();
        String documentId = name.substring(0, name.length() - ".json".length());
        try {
            Instant modified = Files.getLastModifiedTime(meta).toInstant();
            if (modified.isBefore(Instant.now().minus(retention)) || !Files.exists(pdfPath(documentId))) {
                deleteFiles(documentId);
                return;
            }
            Stored stored = mapper.readValue(Files.readAllBytes(meta), Stored.class);
            Entry entry = new Entry(stored.fileName(), null, stored.pageCount());
            entry.lastAccessed = modified;
            if (stored.edits() != null) {
                stored.edits().forEach((page, runs) -> {
                    Map<Integer, RunEdit> byRun = new ConcurrentHashMap<>();
                    runs.forEach((run, edit) -> byRun.put(Integer.valueOf(run), edit));
                    entry.editsByPage.put(Integer.valueOf(page), byRun);
                });
            }
            if (stored.added() != null) {
                stored.added().forEach((page, boxes) -> entry.addedByPage.put(Integer.valueOf(page), List.copyOf(boxes)));
            }
            if (stored.images() != null) {
                stored.images().forEach((page, boxes) -> entry.imagesByPage.put(Integer.valueOf(page), List.copyOf(boxes)));
            }
            if (stored.redactions() != null) {
                stored.redactions().forEach((page, areas) ->
                        entry.redactionsByPage.put(Integer.valueOf(page), List.copyOf(areas)));
            }
            entry.imageIds.addAll(savedImageIds(documentId));
            documents.put(documentId, entry);
        } catch (IOException | RuntimeException e) {
            log.warn("Skipping the unreadable saved document {}: {}", documentId, e.toString());
        }
    }

    private void deleteFiles(String documentId) {
        try {
            for (String imageId : savedImageIds(documentId)) {
                Files.deleteIfExists(imagePath(documentId, imageId));
            }
            Files.deleteIfExists(pdfPath(documentId));
            Files.deleteIfExists(metaPath(documentId));
        } catch (IOException e) {
            log.warn("Could not delete the expired document {}: {}", documentId, e.toString());
        }
    }

    /** The pictures kept on disk for a document: files named {@code <id>.<imageId>.img} next to its PDF. */
    private List<String> savedImageIds(String documentId) {
        String prefix = safe(documentId) + ".";
        try (Stream<Path> files = Files.list(dataDir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith(prefix) && name.endsWith(".img"))
                    .map(name -> name.substring(prefix.length(), name.length() - ".img".length()))
                    .filter(id -> id.matches("[A-Za-z0-9-]{1,64}"))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private Path imagePath(String documentId, String imageId) {
        if (!imageId.matches("[A-Za-z0-9-]{1,64}")) {
            throw new NoSuchElementException("Unknown picture id: " + imageId);
        }
        return dataDir.resolve(safe(documentId) + "." + imageId + ".img");
    }

    private Path pdfPath(String documentId) {
        return dataDir.resolve(safe(documentId) + ".pdf");
    }

    private Path metaPath(String documentId) {
        return dataDir.resolve(safe(documentId) + ".json");
    }

    /** Ids are generated UUIDs; refuse anything else so an id can never name a file outside the data folder. */
    private static String safe(String documentId) {
        if (!documentId.matches("[A-Za-z0-9-]{1,64}")) {
            throw new NoSuchElementException("Unknown document id: " + documentId);
        }
        return documentId;
    }

    private static final class Entry {
        final String fileName;
        /** Read from disk on first use after a restart. */
        volatile byte[] content;
        final int pageCount;
        /** pageIndex to (run index to edit). */
        final Map<Integer, Map<Integer, RunEdit>> editsByPage = new ConcurrentHashMap<>();
        /** pageIndex to the text boxes added to that page. */
        final Map<Integer, List<AddedText>> addedByPage = new ConcurrentHashMap<>();
        /** pageIndex to the picture boxes added to that page. */
        final Map<Integer, List<AddedImage>> imagesByPage = new ConcurrentHashMap<>();
        /** pageIndex to the areas redacted on export. */
        final Map<Integer, List<Redaction>> redactionsByPage = new ConcurrentHashMap<>();
        /** Pictures uploaded for the document; their bytes are on disk, or here when there is no data folder. */
        final java.util.Set<String> imageIds = ConcurrentHashMap.newKeySet();
        final Map<String, byte[]> imagesInMemory = new ConcurrentHashMap<>();
        volatile Instant lastAccessed = Instant.now();

        Entry(String fileName, byte[] content, int pageCount) {
            this.fileName = fileName;
            this.content = content;
            this.pageCount = pageCount;
        }
    }
}
