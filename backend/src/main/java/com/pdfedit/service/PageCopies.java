package com.pdfedit.service;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;

/**
 * Pages copied out of documents into a new one keep what belongs to them beyond their content - the document's
 * information, its bookmarks, its form fields, links between its pages - and bring nothing else along.
 *
 * <p>{@code importPage} copies a page's dictionary, not what points from it into the rest of its document: an
 * annotation names its page ({@code /P}), a link names the page it leads to, a form field lists its boxes on every
 * page. Saved as they were, each of those took a page of the source into the file, and with the page, through its
 * {@code /Parent}, the source's whole page tree: a single exported page held every page of the original, unseen -
 * redacted text included. {@link #finish} points them at the copies instead, rebuilds the bookmarks and the form
 * from what was copied, and cuts whatever still leads to a page that is not one of the new document's own.
 */
public final class PageCopies {

    private static final COSName WIDGET = COSName.getPDFName("Widget");
    private static final List<COSName> CATALOG_KEYS = List.of(COSName.getPDFName("Lang"),
            COSName.getPDFName("PageMode"), COSName.getPDFName("PageLayout"), COSName.getPDFName("ViewerPreferences"));
    private static final List<COSName> FORM_KEYS = List.of(COSName.getPDFName("DA"), COSName.getPDFName("Q"),
            COSName.getPDFName("NeedAppearances"), COSName.getPDFName("SigFlags"));
    /** Deeper than any real bookmark tree; guards against a malformed, endless one. */
    private static final int MAX_DEPTH = 64;

    /** The documents pages were copied from, in the order of their first page. */
    private final List<PDDocument> sources = new ArrayList<>();
    /** For each page of the new document, in order: the page it is a copy of, and that page's document. */
    private final List<PDPage> fromPages = new ArrayList<>();
    private final List<PDDocument> fromDocuments = new ArrayList<>();

    /** Records that the next page of the new document is a copy of {@code page} of {@code source}. */
    public void add(PDDocument source, PDPage page) {
        if (sources.stream().noneMatch(s -> s == source)) {
            sources.add(source);
        }
        fromPages.add(page);
        fromDocuments.add(source);
    }

    /**
     * Finishes {@code target}, whose pages are the recorded copies in the recorded order.
     *
     * @param bookmarks   whether the bookmarks leading to the copied pages go along
     * @param interactive whether the pages keep their form fields and links (not for pictures of the pages)
     */
    public void finish(PDDocument target, boolean bookmarks, boolean interactive) throws IOException {
        List<PDPage> pages = new ArrayList<>();
        target.getPages().forEach(pages::add);
        if (pages.size() != fromPages.size()) {
            throw new IllegalStateException(pages.size() + " pages for " + fromPages.size() + " copies");
        }
        if (sources.isEmpty()) {
            return;
        }
        Map<COSDictionary, PDPage> copyOf = new IdentityHashMap<>();
        for (int i = 0; i < pages.size(); i++) {
            copyOf.putIfAbsent(fromPages.get(i).getCOSObject(), pages.get(i));
        }
        copyInformation(target);
        if (bookmarks) {
            copyBookmarks(target, copyOf);
        }
        if (interactive) {
            for (int i = 0; i < pages.size(); i++) {
                fixAnnotations(fromDocuments.get(i), pages.get(i), copyOf);
            }
            copyForm(target, pages);
        }
        cutPagesNotIn(target);
    }

    // ----- information -----

    private void copyInformation(PDDocument target) {
        PDDocument first = sources.get(0);
        COSDictionary info = first.getDocumentInformation().getCOSObject();
        target.getDocumentInformation().getCOSObject().addAll(info);
        target.getDocumentInformation().setModificationDate(Calendar.getInstance());
        COSDictionary from = first.getDocumentCatalog().getCOSObject();
        COSDictionary to = target.getDocumentCatalog().getCOSObject();
        for (COSName key : CATALOG_KEYS) {
            COSBase value = from.getDictionaryObject(key);
            if (value != null) {
                to.setItem(key, value);
            }
        }
    }

    // ----- bookmarks -----

    private void copyBookmarks(PDDocument target, Map<COSDictionary, PDPage> copyOf) {
        PDDocumentOutline outline = new PDDocumentOutline();
        Set<COSDictionary> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PDDocument source : sources) {
            PDDocumentOutline from = source.getDocumentCatalog().getDocumentOutline();
            if (from != null) {
                copyChildren(source, from, outline, copyOf, seen, 0);
            }
        }
        if (outline.hasChildren()) {
            target.getDocumentCatalog().setDocumentOutline(outline);
        }
    }

    private void copyChildren(PDDocument source, PDOutlineNode from, PDOutlineNode to,
            Map<COSDictionary, PDPage> copyOf, Set<COSDictionary> seen, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        PDOutlineItem item = from.getFirstChild();
        while (item != null && seen.add(item.getCOSObject())) {
            PDOutlineItem copy = copyItem(source, item, copyOf, seen, depth);
            if (copy != null) {
                to.addLast(copy);
            }
            item = item.getNextSibling();
        }
    }

    /** The copy of a bookmark, or null when neither it nor any bookmark under it leads anywhere copied. */
    private PDOutlineItem copyItem(PDDocument source, PDOutlineItem from, Map<COSDictionary, PDPage> copyOf,
            Set<COSDictionary> seen, int depth) {
        PDOutlineItem copy = new PDOutlineItem();
        copy.setTitle(from.getTitle());
        copy.setBold(from.isBold());
        copy.setItalic(from.isItalic());
        if (from.getCOSObject().getDictionaryObject(COSName.C) instanceof COSArray colour) {
            copy.getCOSObject().setItem(COSName.C, colour);
        }
        copyChildren(source, from, copy, copyOf, seen, depth + 1);

        PDPageDestination destination = null;
        PDAction action = null;
        try {
            PDDestination own = from.getDestination();
            if (own == null && from.getAction() instanceof PDActionGoTo goTo) {
                own = goTo.getDestination();
            } else if (own == null) {
                action = from.getAction(); // a web address or another file: still works from the new document
            }
            destination = explicit(source, own);
        } catch (IOException | RuntimeException unreadable) {
            // a damaged bookmark leads nowhere; its children may still
        }
        PDPage page = destination == null ? null : copyOf.get(pageOf(source, destination));
        if (page != null) {
            copy.setDestination(retarget(destination, page));
        } else if (action != null) {
            copy.setAction(action);
        } else if (copy.hasChildren()) {
            // Its own page was left out: it opens the first page under it instead.
            copy.setDestination(firstDestination(copy));
        } else {
            return null;
        }
        if (from.isNodeOpen() && copy.hasChildren()) {
            copy.openNode();
        }
        return copy;
    }

    private static PDDestination firstDestination(PDOutlineItem item) {
        for (PDOutlineItem child : item.children()) {
            try {
                if (child.getDestination() != null) {
                    return child.getDestination();
                }
            } catch (IOException ignored) {
                // our own copies: always readable
            }
            PDDestination below = firstDestination(child);
            if (below != null) {
                return below;
            }
        }
        return null;
    }

    // ----- annotations: their page, and where links lead -----

    private static void fixAnnotations(PDDocument source, PDPage page, Map<COSDictionary, PDPage> copyOf)
            throws IOException {
        List<PDAnnotation> kept = new ArrayList<>();
        for (PDAnnotation annotation : page.getAnnotations()) {
            if (annotation.getCOSObject().containsKey(COSName.P)) {
                annotation.setPage(page);
            }
            if (annotation instanceof PDAnnotationLink link && !retargetLink(source, link, copyOf)) {
                continue; // it led to a page that was not copied
            }
            kept.add(annotation);
        }
        // A list of its own: the source page's list is the same object, and is left as it was.
        page.setAnnotations(kept);
    }

    /** Points a link to another page at that page's copy; false when the page was not copied. */
    private static boolean retargetLink(PDDocument source, PDAnnotationLink link, Map<COSDictionary, PDPage> copyOf) {
        try {
            PDDestination own = link.getDestination();
            PDActionGoTo goTo = own == null && link.getAction() instanceof PDActionGoTo g ? g : null;
            PDPageDestination destination = explicit(source, own != null ? own : goTo == null ? null : goTo.getDestination());
            if (destination == null) {
                return own == null && goTo == null; // a web address, another file, or nothing: left as it is
            }
            PDPage page = copyOf.get(pageOf(source, destination));
            if (page == null) {
                return false;
            }
            if (goTo != null) {
                goTo.setDestination(retarget(destination, page));
            } else {
                link.setDestination(retarget(destination, page));
            }
            return true;
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    // ----- form fields -----

    /** The fields with a box on the new document's pages, each trimmed to those boxes, as its form. */
    private void copyForm(PDDocument target, List<PDPage> pages) {
        Set<COSDictionary> boxes = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PDPage page : pages) {
            if (page.getCOSObject().getDictionaryObject(COSName.ANNOTS) instanceof COSArray annotations) {
                for (int i = 0; i < annotations.size(); i++) {
                    if (annotations.getObject(i) instanceof COSDictionary a && WIDGET.equals(a.getCOSName(COSName.SUBTYPE))) {
                        boxes.add(a);
                    }
                }
            }
        }
        if (boxes.isEmpty()) {
            return;
        }
        COSDictionary form = null;
        COSArray fields = new COSArray();
        Set<String> names = new HashSet<>();
        Set<COSDictionary> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PDDocument source : sources) {
            PDAcroForm from = source.getDocumentCatalog().getAcroForm(null);
            if (from == null || !(from.getCOSObject().getDictionaryObject(COSName.FIELDS) instanceof COSArray roots)) {
                continue;
            }
            if (form == null) {
                form = new COSDictionary();
            }
            mergeFormSettings(from.getCOSObject(), form);
            for (int i = 0; i < roots.size(); i++) {
                if (roots.getObject(i) instanceof COSDictionary field && keepBoxes(field, boxes, seen, 0)) {
                    String name = field.getString(COSName.T);
                    if (name != null && !names.add(name)) {
                        // The same name from another document: renamed, or the two would share one value.
                        int n = 2;
                        while (!names.add(name + "_" + n)) {
                            n++;
                        }
                        field.setItem(COSName.T, new COSString(name + "_" + n));
                    }
                    fields.add(field);
                }
            }
        }
        if (form != null && fields.size() > 0) {
            form.setItem(COSName.FIELDS, fields);
            target.getDocumentCatalog().getCOSObject().setItem(COSName.ACRO_FORM, form);
        }
    }

    private static void mergeFormSettings(COSDictionary from, COSDictionary to) {
        for (COSName key : FORM_KEYS) {
            COSBase value = from.getDictionaryObject(key);
            if (value != null && !to.containsKey(key)) {
                to.setItem(key, value);
            }
        }
        // The fonts the fields are written in, named by their DA: those of every document, the first one's winning.
        if (from.getDictionaryObject(COSName.DR) instanceof COSDictionary resources) {
            COSDictionary merged = to.getDictionaryObject(COSName.DR) instanceof COSDictionary d ? d : new COSDictionary();
            to.setItem(COSName.DR, merged);
            for (Map.Entry<COSName, COSBase> entry : resources.entrySet()) {
                COSBase own = merged.getDictionaryObject(entry.getKey());
                COSBase theirs = resources.getDictionaryObject(entry.getKey());
                if (own == null) {
                    merged.setItem(entry.getKey(), entry.getValue());
                } else if (own instanceof COSDictionary mine && theirs instanceof COSDictionary other && mine != other) {
                    COSDictionary both = new COSDictionary();
                    both.addAll(other);
                    both.addAll(mine);
                    merged.setItem(entry.getKey(), both);
                }
            }
        }
    }

    /**
     * Keeps, under a field, only its boxes on the new document's pages (and the fields that still have one).
     * False when none is left: the field goes.
     */
    private static boolean keepBoxes(COSDictionary field, Set<COSDictionary> boxes, Set<COSDictionary> seen, int depth) {
        if (depth > MAX_DEPTH || !seen.add(field)) {
            return false;
        }
        if (WIDGET.equals(field.getCOSName(COSName.SUBTYPE))) {
            return boxes.contains(field); // a field and its one box in a single dictionary
        }
        if (!(field.getDictionaryObject(COSName.KIDS) instanceof COSArray kids)) {
            return false;
        }
        COSArray kept = new COSArray();
        for (int i = 0; i < kids.size(); i++) {
            COSBase kid = kids.get(i);
            if (kids.getObject(i) instanceof COSDictionary child) {
                boolean keep = WIDGET.equals(child.getCOSName(COSName.SUBTYPE)) && !child.containsKey(COSName.T)
                        ? boxes.contains(child)
                        : keepBoxes(child, boxes, seen, depth + 1);
                if (keep) {
                    kept.add(kid);
                }
            }
        }
        if (kept.size() != kids.size()) {
            field.setItem(COSName.KIDS, kept);
        }
        return kept.size() > 0;
    }

    // ----- destinations -----

    private static PDPageDestination explicit(PDDocument source, PDDestination destination) throws IOException {
        if (destination instanceof PDNamedDestination named) {
            return source.getDocumentCatalog().findNamedDestinationPage(named);
        }
        return destination instanceof PDPageDestination page ? page : null;
    }

    /** The source page a destination leads to, or null (another file, or a damaged destination). */
    private static COSDictionary pageOf(PDDocument source, PDPageDestination destination) {
        if (destination.getPage() != null) {
            return destination.getPage().getCOSObject();
        }
        int number = destination.getPageNumber();
        return number >= 0 && number < source.getNumberOfPages() ? source.getPage(number).getCOSObject() : null;
    }

    /** The same kind of destination (and the same place on the page), on {@code page}. */
    private static PDPageDestination retarget(PDPageDestination destination, PDPage page) {
        COSArray array = new COSArray();
        COSArray from = destination.getCOSObject();
        for (int i = 0; i < from.size(); i++) {
            array.add(from.get(i));
        }
        array.set(0, page.getCOSObject());
        try {
            return (PDPageDestination) PDDestination.create(array);
        } catch (IOException e) {
            throw new IllegalStateException(e); // an array with a page first is always a page destination
        }
    }

    // ----- nothing of other pages -----

    /**
     * Cuts every reference, anywhere in {@code document}, to a page or page tree that is not its own: an entry
     * naming one is removed, an array element becomes null. What is left to save is the document's own pages and
     * what they use.
     */
    public static void cutPagesNotIn(PDDocument document) {
        Set<COSDictionary> own = Collections.newSetFromMap(new IdentityHashMap<>());
        own.add(document.getPages().getCOSObject());
        document.getPages().forEach(page -> own.add(page.getCOSObject()));

        Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<COSBase> todo = new ArrayDeque<>();
        todo.push(document.getDocument().getTrailer());
        while (!todo.isEmpty()) {
            COSBase next = todo.pop();
            COSBase base = next instanceof COSObject object ? object.getObject() : next;
            if (base == null || !visited.add(base)) {
                continue;
            }
            if (base instanceof COSDictionary dictionary) {
                for (COSName key : new ArrayList<>(dictionary.keySet())) {
                    COSBase value = dictionary.getDictionaryObject(key);
                    if (foreignPage(value, own)) {
                        dictionary.removeItem(key);
                    } else if (value instanceof COSDictionary || value instanceof COSArray) {
                        todo.push(value);
                    }
                }
            } else if (base instanceof COSArray array) {
                for (int i = 0; i < array.size(); i++) {
                    COSBase value = array.getObject(i);
                    if (foreignPage(value, own)) {
                        array.set(i, COSNull.NULL);
                    } else if (value instanceof COSDictionary || value instanceof COSArray) {
                        todo.push(value);
                    }
                }
            }
        }
    }

    private static boolean foreignPage(COSBase value, Set<COSDictionary> own) {
        if (!(value instanceof COSDictionary dictionary) || own.contains(dictionary)) {
            return false;
        }
        COSName type = dictionary.getCOSName(COSName.TYPE);
        return COSName.PAGE.equals(type) || COSName.PAGES.equals(type);
    }
}
