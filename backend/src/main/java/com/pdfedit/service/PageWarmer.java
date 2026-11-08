package com.pdfedit.service;

import java.util.LinkedHashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

/**
 * Reads pages ahead, in the background, so the editor need not wait when they are opened: telling a page's lines
 * and fonts takes seconds the first time on a PDF with many embedded fonts (one made by Ghostscript can carry 200),
 * and a fraction of a second once its fonts are known. The pages next to the one being edited go first, then the
 * rest of a document just opened. One page at a time, at low priority: the work the user waits for comes first.
 */
@Service
public class PageWarmer implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PageWarmer.class);
    /** A document with more pages than this is read ahead only near the page being edited. */
    static final int MAX_WHOLE_DOCUMENT = 300;
    /** Pages waiting at most; older wishes give way to newer ones. */
    private static final int MAX_WAITING = 2000;

    private final PdfDocumentStore store;
    private final TextEditService textEditService;
    /** The pages still to read, the next first ("documentId/page"). */
    private final LinkedHashSet<String> waiting = new LinkedHashSet<>();
    /** The pages read already, so they are not read again. */
    private final Set<String> done = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Thread worker;
    private volatile boolean stopped;

    public PageWarmer(PdfDocumentStore store, TextEditService textEditService) {
        this.store = store;
        this.textEditService = textEditService;
        this.worker = Thread.ofPlatform().daemon().name("page-warmer").priority(Thread.MIN_PRIORITY)
                .unstarted(this::work);
        worker.start();
    }

    /** A document just opened: its pages, first to last, after whatever is waiting already. */
    public void wholeDocument(String documentId, int pageCount) {
        if (pageCount > MAX_WHOLE_DOCUMENT) {
            return;
        }
        synchronized (waiting) {
            for (int p = 0; p < pageCount; p++) {
                String key = key(documentId, p);
                if (!done.contains(key) && waiting.size() < MAX_WAITING) {
                    waiting.add(key);
                }
            }
            waiting.notifyAll();
        }
    }

    /** A page being edited: the pages after and before it are read next, before anything else waiting. */
    public void around(String documentId, int pageIndex, int pageCount) {
        LinkedHashSet<String> first = new LinkedHashSet<>();
        for (int p : new int[] {pageIndex + 1, pageIndex - 1, pageIndex + 2}) {
            if (p >= 0 && p < pageCount && !done.contains(key(documentId, p))) {
                first.add(key(documentId, p));
            }
        }
        if (first.isEmpty()) {
            return;
        }
        synchronized (waiting) {
            waiting.removeAll(first);
            LinkedHashSet<String> rest = new LinkedHashSet<>(waiting);
            waiting.clear();
            waiting.addAll(first);
            for (String k : rest) {
                if (waiting.size() >= MAX_WAITING) {
                    break;
                }
                waiting.add(k);
            }
            waiting.notifyAll();
        }
    }

    /** A page the editor read itself: nothing more to do for it. */
    public void read(String documentId, int pageIndex) {
        String key = key(documentId, pageIndex);
        done.add(key);
        synchronized (waiting) {
            waiting.remove(key);
        }
    }

    private void work() {
        while (!stopped) {
            String next;
            synchronized (waiting) {
                while (waiting.isEmpty() && !stopped) {
                    try {
                        waiting.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (stopped) {
                    return;
                }
                next = waiting.iterator().next();
                waiting.remove(next);
            }
            if (!done.add(next)) {
                continue;
            }
            int slash = next.lastIndexOf('/');
            String documentId = next.substring(0, slash);
            int page = Integer.parseInt(next.substring(slash + 1));
            try {
                textEditService.describePage(store.getContent(documentId), page,
                        store.pageEditsSnapshot(documentId, page));
            } catch (Exception | LinkageError e) {
                // The document went away, or the page cannot be read: the editor will say so if it is opened.
                log.debug("Could not read page {} of {} ahead: {}", page, documentId, e.toString());
            }
        }
    }

    /** Whether the page was read already (ahead or by the editor). */
    boolean isRead(String documentId, int pageIndex) {
        return done.contains(key(documentId, pageIndex));
    }

    private static String key(String documentId, int pageIndex) {
        return documentId + "/" + pageIndex;
    }

    @Override
    public void destroy() {
        stopped = true;
        synchronized (waiting) {
            waiting.notifyAll();
        }
        worker.interrupt();
    }
}
