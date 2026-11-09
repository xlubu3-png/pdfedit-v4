package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;

import org.junit.jupiter.api.Test;

/** Which Office program opens a file, what the PDF is called, and what the user is told when Office cannot. */
class OfficeConverterTest {

    @Test
    void eachKindOfFileGoesToItsProgram() {
        assertThat(OfficeConverter.programFor("견적서.XLSX")).isEqualTo("excel");
        assertThat(OfficeConverter.programFor("a.xls")).isEqualTo("excel");
        assertThat(OfficeConverter.programFor("보고서.docx")).isEqualTo("word");
        assertThat(OfficeConverter.programFor("발표.pptx")).isEqualTo("powerpoint");
        assertThat(OfficeConverter.programFor("문서.pdf")).isNull();
        assertThat(OfficeConverter.programFor("문서.hwp")).isNull();
        assertThat(OfficeConverter.pdfName("견적서.xlsx")).isEqualTo("견적서.pdf");
        assertThat(OfficeConverter.pdfName("a.b.docx")).isEqualTo("a.b.pdf");
    }

    @Test
    void theUserIsToldWhyOfficeCouldNot() {
        assertThat(OfficeConverter.reason("Retrieving the COM class factory failed: 80040154 Class not registered", "excel"))
                .isEqualTo("이 PC에 MS Excel이 설치되어 있지 않아 열 수 없습니다.");
        assertThat(OfficeConverter.reason("The password is incorrect", "word")).contains("암호가 걸린 Office 문서");
        assertThat(OfficeConverter.reason("something else", "powerpoint")).contains("MS PowerPoint로 이 문서를");
    }

    @Test
    void elsewhereThanWindowsItSaysWhereItWorks() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return;
        }
        assertThatThrownBy(() -> new OfficeConverter().toPdf(new byte[] {1, 2, 3}, "견적서.xlsx"))
                .isInstanceOf(InvalidEditException.class)
                .hasMessageContaining("MS Office가 설치된 Windows PC에서만");
    }

    @Test
    void theScriptOpensReadOnlyAndExportsPdf() {
        assertThat(OfficeConverter.SCRIPT).contains("Word.Application", "Excel.Application", "PowerPoint.Application",
                "ExportAsFixedFormat($dst, 17)", "ExportAsFixedFormat(0, $dst)", "SaveAs($dst, 32)", "$app.Quit()");
    }

    @Test
    void theScriptSwitchesMacrosOffAndNeverAsksForAPassword() {
        // Macros off in each program, before the document is opened.
        assertThat(OfficeConverter.SCRIPT.split("AutomationSecurity = 3", -1)).hasSize(4);
        for (String open : new String[] {"Documents.Open(", "Workbooks.Open(", "Presentations.Open("}) {
            assertThat(OfficeConverter.SCRIPT.indexOf("AutomationSecurity = 3"))
                    .isLessThan(OfficeConverter.SCRIPT.indexOf(open));
        }
        assertThat(OfficeConverter.SCRIPT).contains("'::__wintech_no_password__::'");
        // A PowerPoint the user already had open is not closed.
        assertThat(OfficeConverter.SCRIPT).contains("if ($mine) { $app.Quit() }");
    }

    @Test
    void onlyAnOfficeProgramTheScriptStartedIsEnded(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws java.io.IOException {
        // No number written: nothing to end.
        OfficeConverter.endOffice(dir.resolve("none.pid"));
        // A number that is not an Office program (this test itself) is left alone.
        java.nio.file.Path pid = dir.resolve("office.pid");
        java.nio.file.Files.writeString(pid, ProcessHandle.current().pid() + "\r\n");
        OfficeConverter.endOffice(pid);
        java.nio.file.Files.writeString(pid, "not a number");
        OfficeConverter.endOffice(pid);
        assertThat(ProcessHandle.current().isAlive()).isTrue();
    }

    @Test
    void whatTheScriptSaidIsReadFromItsFile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws java.io.IOException {
        java.nio.file.Path output = dir.resolve("said.txt");
        java.nio.file.Files.writeString(output, "The password is incorrect");
        assertThat(OfficeConverter.said(output)).isEqualTo("The password is incorrect");
        assertThat(OfficeConverter.said(dir.resolve("missing.txt"))).isEmpty();
    }
}
