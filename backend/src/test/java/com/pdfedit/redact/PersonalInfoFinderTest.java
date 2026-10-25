package com.pdfedit.redact;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import com.pdfedit.redact.PersonalInfoFinder.Kind;
import com.pdfedit.redact.PersonalInfoFinder.Match;

class PersonalInfoFinderTest {

    private static final Set<Kind> ALL = EnumSet.complementOf(EnumSet.of(Kind.TERM));

    /** What is found in a page with one line per entry. */
    private static List<Match> found(Set<Kind> kinds, String term, String... lines) throws IOException {
        byte[] pdf = RedactorTest.page((doc, page, cs) -> {
            for (int i = 0; i < lines.length; i++) {
                RedactorTest.line(cs, 72, 760 - i * 30, lines[i]);
            }
        });
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return PersonalInfoFinder.find(doc, 0, kinds, term);
        }
    }

    private static List<String> texts(List<Match> matches) {
        return matches.stream().map(Match::text).toList();
    }

    @Test
    void residentNumbersNeedARealBirthDate() throws IOException {
        List<Match> matches = found(ALL, null, "RRN 900101-1234567", "Not 901399-1234567", "Alien 850505-5123456");

        assertThat(matches).extracting(Match::kind).containsOnly(Kind.RRN);
        assertThat(texts(matches)).containsExactly("900101-1234567", "850505-5123456");
    }

    @Test
    void phoneNumbersWithDashesDotsOrSpaces() throws IOException {
        List<Match> matches = found(ALL, null, "Mobile 010-1234-5678", "Mobile 010 9876 5432", "Office 02-345-6789",
                "Fax 031.123.4567");

        assertThat(matches).extracting(Match::kind)
                .containsExactly(Kind.MOBILE, Kind.MOBILE, Kind.PHONE, Kind.PHONE);
        assertThat(texts(matches)).containsExactly("010-1234-5678", "010 9876 5432", "02-345-6789", "031.123.4567");
    }

    @Test
    void cardNumbersMustPassTheLuhnCheck() throws IOException {
        List<Match> matches = found(ALL, null, "Card 4111-1111-1111-1111", "Bad 4111-1111-1111-1112");

        assertThat(matches).extracting(Match::kind).containsExactly(Kind.CARD);
        assertThat(texts(matches)).containsExactly("4111-1111-1111-1111");
    }

    @Test
    void emailBusinessAccountDriverAndPassport() throws IOException {
        List<Match> matches = found(ALL, null, "Mail hong.gildong@example.co.kr", "Biz 123-45-67890",
                "Account 110-234-567890", "License 11-22-123456-01", "Passport M12345678", "Date 2024-01-15");

        assertThat(matches).extracting(Match::kind)
                .containsExactly(Kind.EMAIL, Kind.BUSINESS, Kind.ACCOUNT, Kind.DRIVER, Kind.PASSPORT);
    }

    @Test
    void onlyTheKindsAskedForAreReported() throws IOException {
        List<Match> matches = found(EnumSet.of(Kind.EMAIL), null, "Mobile 010-1234-5678 a@b.kr");

        assertThat(texts(matches)).containsExactly("a@b.kr");
    }

    @Test
    void aTermIsFoundIgnoringCaseAndItsAreaCoversItsLetters() throws IOException {
        List<Match> matches = found(Set.of(), "secret", "The SECRET plan", "no match here");

        assertThat(matches).hasSize(1);
        Match m = matches.get(0);
        assertThat(m.kind()).isEqualTo(Kind.TERM);
        assertThat(m.text()).isEqualTo("SECRET");
        // 12pt letters on the baseline at y = 760: the area reaches below and above them.
        assertThat(m.y()).isLessThan(760f);
        assertThat(m.y() + m.height()).isGreaterThan(768f);
        assertThat(m.x()).isGreaterThan(72f);
    }
}
