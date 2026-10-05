package com.pdfedit.controller;

import java.io.IOException;
import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pdfedit.dto.FindMatch;
import com.pdfedit.dto.FindRequest;
import com.pdfedit.dto.ReplaceRequest;
import com.pdfedit.dto.ReplaceResult;
import com.pdfedit.service.TextSearchService;

/** Find and replace over the text lines of several pages. */
@RestController
@RequestMapping("/api/v1/pdf")
public class SearchController {

    private final TextSearchService search;

    public SearchController(TextSearchService search) {
        this.search = search;
    }

    @PostMapping("/search")
    public List<FindMatch> find(@RequestBody FindRequest request) throws IOException {
        return search.find(request.pages(), request.query(), Boolean.TRUE.equals(request.matchCase()));
    }

    @PostMapping("/replace")
    public ReplaceResult replace(@RequestBody ReplaceRequest request) throws IOException {
        return search.replace(request.pages(), request.find(), request.replace(),
                Boolean.TRUE.equals(request.matchCase()));
    }
}
