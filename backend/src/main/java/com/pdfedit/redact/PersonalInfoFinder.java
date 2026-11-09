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
        CORPORATE("법인등록번호"),
        VEHICLE("차량번호"),
        VIN("차대번호"),
        NAME("사람 이름(성명 칸)"),
        ADDRESS("주소"),
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

    /** {@code group}: the part of the match that is covered (0: all of it; a name after its "성명:" label). */
    private record Rule(Kind kind, Pattern pattern, Predicate<Matcher> valid, int group) {
        Rule(Kind kind, Pattern pattern, Predicate<Matcher> valid) {
            this(kind, pattern, valid, 0);
        }
    }

    /** The letters a number plate's middle may be (자가용·영업용·렌터카·택배). */
    private static final String PLATE_LETTER = "[가나다라마거너더러머버서어저고노도로모보소오조구누두루무부수우주아바사자배하허호]";
    /** The 시·도 an address or an old number plate starts with. */
    private static final String SIDO = "(?:서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충북|충남|전북|전남|경북|경남|제주)";
    /** Labels a person's name follows (spaced out or not): 성명, 성함, 이름, 대표자, 담당자, 신청인 ... */
    private static final String NAME_LABEL = "(?:성\\s?명|성\\s?함|이\\s?름|대\\s?표\\s?자|담\\s?당\\s?자|신\\s?청\\s?인|"
            + "소\\s?유\\s?자|수\\s?령\\s?인|작\\s?성\\s?자|보\\s?호\\s?자)";
    /** Common family names: a word after a name label is taken for a name only when it starts with one. */
    private static final String SURNAMES = "김이박최정강조윤장임한오서신권황안송류유전홍고문양손배백허남심노하곽성차주우구민진나지엄채원천방공현함변염여추도소석선설마길연위표명기반왕금옥육인맹제모탁국어은편용예봉경사부황";

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
            // +82 numbers: +82 10-1234-5678, +82-2-123-4567, +82 (0)10 1234 5678.
            new Rule(Kind.MOBILE, Pattern.compile("\\+82[-. ]?(?:\\(0\\)\\s?)?1[016789][-. ]?\\d{3,4}[-. ]?\\d{4}" + NOT_DIGIT_AFTER),
                    m -> true),
            new Rule(Kind.PHONE, Pattern.compile("\\+82[-. ]?(?:\\(0\\)\\s?)?(?:2|3[1-3]|4[1-4]|5[1-5]|6[1-4]|70)[-. ]?\\d{3,4}[-. ]?\\d{4}"
                    + NOT_DIGIT_AFTER), m -> true),
            new Rule(Kind.EMAIL, Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), m -> true),
            new Rule(Kind.BUSINESS, Pattern.compile(NOT_DIGIT_BEFORE + "\\d{3}-\\d{2}-\\d{5}" + NOT_DIGIT_AFTER), m -> true),
            new Rule(Kind.ACCOUNT, Pattern.compile(NOT_DIGIT_BEFORE + "\\d{2,6}-\\d{2,6}-\\d{2,8}(?:-\\d{1,3})?"
                    + NOT_DIGIT_AFTER), m -> {
                        int digits = m.group().replaceAll("\\D", "").length();
                        return digits >= 10 && digits <= 14;
                    }),
            new Rule(Kind.PASSPORT, Pattern.compile("(?<![A-Za-z0-9])[MSRODG](?:\\d{8}|\\d{3}[A-Z]\\d{4})(?![A-Za-z0-9])"),
                    m -> true),
            // 법인등록번호 6-7: the back cannot start as a person's does (1-8), and its last digit checks the others.
            new Rule(Kind.CORPORATE, Pattern.compile(NOT_DIGIT_BEFORE + "\\d{6}\\s?[-‐–]\\s?[09]\\d{6}" + NOT_DIGIT_AFTER),
                    m -> corporate(m.group())),
            // 차량번호: 12가3456, 123가4567, 서울12가3456 (old plates with the 시·도).
            new Rule(Kind.VEHICLE, Pattern.compile("(?<![가-힣0-9])(?:" + SIDO + "\\s?)?\\d{2,3}\\s?" + PLATE_LETTER
                    + "\\s?\\d{4}(?![0-9])"), m -> true),
            // 차대번호 (VIN): 17 letters and digits, no I, O or Q, both letters and digits in it.
            // Read off a scan it may have a space in it ("KMHA B81CBNU123456").
            new Rule(Kind.VIN, Pattern.compile("(?<![A-Za-z0-9])[A-HJ-NPR-Z0-9](?: ?[A-HJ-NPR-Z0-9]){16}(?![A-Za-z0-9])"), m -> {
                String v = m.group().replace(" ", "");
                return v.chars().filter(Character::isDigit).count() >= 3 && v.chars().filter(Character::isLetter).count() >= 3;
            }),
            // A name after a label: "성명: 홍길동", "담당자 : 이영희", "성 명 김철수" - the name is covered, not the label.
            new Rule(Kind.NAME, Pattern.compile(NAME_LABEL + "\\s*(?:\\(\\s?한\\s?글\\s?\\)\\s*)?[:：]?\\s*([" + SURNAMES
                    + "][가-힣]{1,3})(?![가-힣])"), m -> true, 1),
            // An address: 시·도, 시·군·구, then a road and its building number or a 동·리 and its 번지 - the part after the
            // 시·군·구 is covered (the 시·도 and 시·군·구 alone tell little, as kordoc leaves them).
            new Rule(Kind.ADDRESS, Pattern.compile(SIDO + "[가-힣]{0,5}\\s+[가-힣]{1,5}(?:시|군|구)(?:\\s+[가-힣]{1,5}구)?\\s+"
                    + "((?:[가-힣0-9]{1,12}(?:로|길)\\s?\\d{1,4}(?:-\\d{1,4})?(?:번길\\s?\\d{1,4})?|[가-힣0-9]{1,8}(?:동|리|읍|면)\\s?"
                    + "\\d{1,5}(?:-\\d{1,4})?(?:번지)?)(?:,?\\s?[가-힣A-Za-z0-9]{0,15}\\s?\\d{1,5}(?:동|호|층))*(?:\\s?\\([가-힣0-9,. ]{1,20}\\))?)"),
                    m -> true, 1));

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
                    int start = m.start(rule.group());
                    int end = m.end(rule.group());
                    Match match = rule.valid().test(m) && free(taken, start, end)
                            ? match(line, rule.kind(), start, end) : null;
                    if (match != null) {
                        taken.add(new int[] {start, end});
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

    /** Whether a 법인등록번호's last digit checks the twelve before it (weights 1, 2, 1, 2 ...). */
    static boolean corporate(String number) {
        String d = number.replaceAll("\\D", "");
        if (d.length() != 13) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (d.charAt(i) - '0') * (i % 2 == 0 ? 1 : 2);
        }
        return (10 - sum % 10) % 10 == d.charAt(12) - '0';
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
