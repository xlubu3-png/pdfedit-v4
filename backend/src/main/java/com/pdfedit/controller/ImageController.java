package com.pdfedit.controller;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.pdfedit.service.ImageDrawing;
import com.pdfedit.service.InvalidEditException;
import com.pdfedit.service.PdfDocumentStore;
import com.pdfedit.service.TextEditService;
import com.pdfedit.text.AddedImage;

/** Pictures (stamps, signatures, logos) put on the pages of an uploaded document. */
@RestController
@RequestMapping("/api/v1/pdf/documents/{documentId}")
public class ImageController {

    private final PdfDocumentStore store;
    private final TextEditService textEditService;

    public ImageController(PdfDocumentStore store, TextEditService textEditService) {
        this.store = store;
        this.textEditService = textEditService;
    }

    /** Keeps a picture for the document; the pages then place it in boxes by the id returned. */
    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@PathVariable String documentId, @RequestParam("file") MultipartFile file)
            throws IOException {
        ImageDrawing.Upload picture = ImageDrawing.read(file.getBytes());
        String imageId = store.storeImage(documentId, picture.data());
        return Map.of("imageId", imageId, "width", picture.width(), "height", picture.height());
    }

    @GetMapping("/images/{imageId}")
    public ResponseEntity<byte[]> image(@PathVariable String documentId, @PathVariable String imageId) {
        byte[] data = store.imageBytes(documentId, imageId);
        if (data == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ImageDrawing.contentType(data)))
                .cacheControl(CacheControl.noCache())
                .body(data);
    }

    /** Replaces the pictures placed on the page with the submitted list (the browser owns the list). */
    @PutMapping("/pages/{pageIndex}/added-images")
    public ResponseEntity<Void> saveAddedImages(@PathVariable String documentId, @PathVariable int pageIndex,
            @RequestBody List<AddedImage> boxes) throws IOException {
        List<AddedImage> checked = textEditService.checkImages(store.getContent(documentId), pageIndex, boxes);
        for (AddedImage box : checked) {
            if (store.imageBytes(documentId, box.imageId()) == null) {
                throw new InvalidEditException("올리지 않은 그림이 들어 있습니다. 그림을 다시 올려 주세요.");
            }
        }
        store.setAddedImages(documentId, pageIndex, checked);
        return ResponseEntity.noContent().build();
    }
}
