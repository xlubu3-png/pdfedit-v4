package com.pdfedit.redact;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;

import com.pdfedit.text.TextLines;

/**
 * Finds personal information on a page - resident registration numbers, phone numbers, e-mail addresses,
 * account and card numbers and so on - together with the area each one covers, so it can be redacted.
 *
 * <p>Patterns follow the Korean formats. Where a format allows a cheap check (a valid birth date in a
 * resident registration number, the Luhn digit of a card number) it is made, to keep look-alikes out.
 */
public final class PersonalInfoFinder {

    /** What can be looked for. The label is what the screen shows. */
    public enum Kind {
        RRN("주민·외국인등록번호"),
        CARD("카드번호"),
        DRIVER("운전면허번호"),
        MOBILE("휴대전화번호"),
        PHONE("전화번호"),
        EMAIL("이메일"),
        BUSINESS("사업자등록번호"),
        ACCOUNT("계좌번호(추정)"),
        PASSPORT("여권번호"),
        TERM("찾는 글자");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** One finding: its kind, the text as found and the area it covers on the page (PDF user space). */
    public record Match(Kind kind, String text, float x, float y, float width, float height) {
    }

    private record Rule(Kind kind, Pattern pattern, Predicate<Matcher> valid) {
    }

    /** Not a piece of a longer number, such as the last groups of a card number that failed its check. */
    private static final String NOT_DIGIT_BEFORE = "(?<![0-9])(?<![0-9]-)";
    private static final String NOT_DIGIT_AFTER = "(?![0-9])(?!-[0-9])";

    /** In order of precedence: where two overlap, the earlier one wins. */
    private static final List<Rule> RULES = List.of(
            new Rule(Kind.RRN, Pattern.compile(NOT_DIGIT_BEFORE + "(\\d{2})(\\d{2})(\\d{2})\\s?[-‐–]\\s?[1-8]\\d{6}"
                    + NOT_DIGIT_AFTER), PersonalInfoFinder::birthDate),
            new Rule(Kind.CARD, Pattern.compile(NOT_DIGIT_BEFORE + "\\d{4}[- ]\\d{4}[- ]\\d{4}[- ]\\d{4}" + NOT_DIGIT_AFTER),
                    m -> luhn(m.group())),
            new Rule(Kind.DRIVER, Pattern.compile(NOT_DIGIT_BEFORE + "\\d{2}-\\d{2}-\\d{6}-\\d{2}" + NOT_DIGIT_AFTER),
                    m -> true),
            new Rule(Kind.MOBILE, Pattern.compile(NOT_DIGIT_BEFORE + "01[016789][-. )]?\\d{3,4}[-. ]?\\d{4}"
                    + NOT_DIGIT_AFTER), m -> true),
            new Rule(Kind.PHONE, Pattern.compile(NOT_DIGIT_BEFORE + "\\(?0(?:2|3[1-3]|4[1-4]|5[1-5]|6[1-4]|70|50\\d)\\)?"
                    + "[-. )]?\\d{3,4}[-. ]\\d{4}" + NOT_DIGIT_AFTER), m -> true),
            new Rule(Kind.EMAIL, Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), m -> true),
            new Rule(Kind.BUSINESS, Pattern.compile(NOT_DIGIT_BEFORE + "\\d{3}-\\d{2}-\\d{5}" + NOT_DIGIT_AFTER), m -> true),
            new Rule(Kind.ACCOUNT, Pattern.compile(NOT_DIGIT_BEFORE + "\\d{2,6}-\\d{2,6}-\\d{2,8}(?:-\\d{1,3})?"
                    + NOT_DIGIT_AFTER), m -> {
                        int digits = m.group().replaceAll("\\D", "").length();
                        return digits >= 10 && digits <= 14;
                    }),
            new Rule(Kind.PASSPORT, Pattern.compile("(?<![A-Za-z0-9])[MSRODG](?:\\d{8}|\\d{3}[A-Z]\\d{4})(?![A-Za-z0-9])"),
                    m -> true));

    private PersonalInfoFinder() {
    }

    /**
     * The findings of the given kinds on one page, in reading order. {@code term}, when not blank, is also
     * looked for as plain text (ignoring case).
     */
    public static List<Match> find(PDDocument doc, int pageIndex, Set<Kind> kinds, String term) throws IOException {
        return find(doc, pageIndex, kinds, term, List.of());
    }

    /**
     * Like {@link #find(PDDocument, int, Set, String)}, also looking in {@code recognized}: lines read off a scanned
     * page, which has no text of its own.
     */
    public static List<Match> find(PDDocument doc, int pageIndex, Set<Kind> kinds, String term,
            List<TextLines.Line> recognized) throws IOException {
        List<Match> found = new ArrayList<>();
        String wanted = term == null ? "" : term.strip().toLowerCase(Locale.ROOT);
        List<TextLines.Line> lines = new ArrayList<>(TextLines.of(doc, pageIndex));
        lines.addAll(recognized);
        for (TextLines.Line line : lines) {
            List<int[]> taken = new ArrayList<>();
            for (Rule rule : RULES) {
                if (!kinds.contains(rule.kind())) {
                    continue;
                }
                Matcher m = rule.pattern().matcher(line.text());
                while (m.find()) {
                    Match match = rule.valid().test(m) && free(taken, m.start(), m.end())
                            ? match(line, rule.kind(), m.start(), m.end()) : null;
                    if (match != null) {
                        taken.add(new int[] {m.start(), m.end()});
                        found.add(match);
                    }
                }
            }
            if (!wanted.isEmpty()) {
                String lower = line.text().toLowerCase(Locale.ROOT);
                for (int at = lower.indexOf(wanted); at >= 0; at = lower.indexOf(wanted, at + wanted.length())) {
                    Match match = free(taken, at, at + wanted.length())
                            ? match(line, Kind.TERM, at, at + wanted.length()) : null;
                    if (match != null) {
                        found.add(match);
                    }
                }
            }
        }
        return found;
    }

    private static boolean free(List<int[]> taken, int start, int end) {
        for (int[] range : taken) {
            if (start < range[1] && end > range[0]) {
                return false;
            }
        }
        return true;
    }

    /** A month and a day that exist: 000000-... or 991399-... is not a resident registration number. */
    private static boolean birthDate(Matcher m) {
        int month = Integer.parseInt(m.group(2));
        int day = Integer.parseInt(m.group(3));
        return month >= 1 && month <= 12 && day >= 1 && day <= 31;
    }

    private static boolean luhn(String number) {
        String digits = number.replaceAll("\\D", "");
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(digits.length() - 1 - i) - '0';
            if (i % 2 == 1) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
        }
        return sum % 10 == 0;
    }

    /** The finding for characters start (inclusive) to end (exclusive) of the line; null if it has no area. */
    private static Match match(TextLines.Line line, Kind kind, int start, int end) {
        TextLines.Area area = line.area(start, end);
        return area == null ? null
                : new Match(kind, line.text().substring(start, end), area.x(), area.y(), area.width(), area.height());
    }
}
