package com.pdfedit.controller;

import java.io.IOException;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.pdfedit.service.StampStore;

/** 도장·서명: the stamps the user registered, to stamp onto documents. */
@RestController
@RequestMapping("/api/v1/pdf/stamps")
public class StampController {

    /** What a stamp is changed to; what is null is left as it is. */
    public record StampChange(String name, Double width, Double height, Double lastX, Double lastY) {
    }

    private final StampStore stamps;

    public StampController(StampStore stamps) {
        this.stamps = stamps;
    }

    @GetMapping
    public List<StampStore.Stamp> list() {
        return stamps.list();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StampStore.Stamp add(@RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name) throws IOException {
        return stamps.add(name, file.getBytes());
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> picture(@PathVariable String id) {
        byte[] picture = stamps.picture(id);
        if (picture == null) {
            return ResponseEntity.notFound().build();
        }
        boolean jpeg = picture.length > 2 && (picture[0] & 0xff) == 0xff && (picture[1] & 0xff) == 0xd8;
        return ResponseEntity.ok().contentType(jpeg ? MediaType.IMAGE_JPEG : MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noCache()).body(picture);
    }

    @PutMapping("/{id}")
    public StampStore.Stamp change(@PathVariable String id, @RequestBody StampChange change) {
        return stamps.update(id, change.name(), change.width(), change.height(), change.lastX(), change.lastY());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        stamps.delete(id);
        return ResponseEntity.noContent().build();
    }
}
