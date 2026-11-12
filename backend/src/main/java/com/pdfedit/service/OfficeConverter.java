package com.pdfedit.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Word, Excel and PowerPoint documents opened as PDFs: the PC's own Microsoft Office prints them to PDF (through a
 * small hidden PowerShell that drives Word, Excel or PowerPoint), so they look exactly as Office shows them - fonts,
 * tables, shapes, charts, an Excel sheet's print area. Then they are PDFs like any other. Only on Windows with
 * Office installed; one at a time (Office does not like being driven twice at once).
 */
@Service
public class OfficeConverter {

    private static final Logger log = LoggerFactory.getLogger(OfficeConverter.class);
    /** A document Office has not printed in this long is not coming (a dialog it waits on, a broken file). */
    private static final long TIMEOUT_SECONDS = 180;

    private final long timeoutSeconds;

    public OfficeConverter() {
        this(TIMEOUT_SECONDS);
    }

    OfficeConverter(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    /** Which Office program opens a file of this name; null when none does. */
    public static String programFor(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.matches(".*\\.(docx?|docm|rtf|odt)$")) {
            return "word";
        }
        if (name.matches(".*\\.(xlsx?|xlsm|xlsb|csv|ods)$")) {
            return "excel";
        }
        if (name.matches(".*\\.(pptx?|pptm|ppsx?|odp)$")) {
            return "powerpoint";
        }
        return null;
    }

    /** Whether the file is one Office opens (and the app then shows as a PDF). */
    public static boolean isOffice(String fileName) {
        return programFor(fileName) != null;
    }

    /** The name the PDF made of it goes by: "견적서.xlsx" becomes "견적서.pdf". */
    public static String pdfName(String fileName) {
        String name = fileName == null || fileName.isBlank() ? "문서" : fileName;
        return name.replaceFirst("\\.[^.]+$", "") + ".pdf";
    }

    /**
     * The document as a PDF, printed by Office.
     *
     * @throws InvalidEditException when this PC cannot (no Windows, no Office) or Office could not open it
     */
    public synchronized byte[] toPdf(byte[] content, String fileName) throws IOException {
        String program = programFor(fileName);
        if (program == null) {
            throw new InvalidEditException("Word·Excel·PowerPoint 문서가 아닙니다.");
        }
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            throw new InvalidEditException("Office 문서(Word·Excel·PowerPoint)는 MS Office가 설치된 Windows PC에서만 열 수 있습니다.");
        }
        Path dir = Files.createTempDirectory("wintech-office-");
        try {
            String extension = fileName.substring(fileName.lastIndexOf('.'));
            Path source = dir.resolve("source" + extension.toLowerCase(Locale.ROOT));
            Path target = dir.resolve("printed.pdf");
            Path script = dir.resolve("print.ps1");
            Files.write(source, forExcel(content, fileName));
            // UTF-8 with a byte order mark: Windows PowerShell 5 reads a script without one as the PC's code page.
            Files.writeString(script, "﻿" + SCRIPT, StandardCharsets.UTF_8);
            Path output = dir.resolve("said.txt");
            Path started = dir.resolve("office.pid");
            // What the script says goes to a file: reading its pipe would wait for a script that never ends.
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy",
                    "Bypass", "-File", script.toString(), source.toString(), target.toString(), program,
                    started.toString())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            boolean done = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                // The Office program the script started is not its child (Windows starts it): ended by its number.
                endOffice(started);
                throw new InvalidEditException("MS Office가 " + Math.max(1, timeoutSeconds / 60) + "분 안에 문서를 PDF로 바꾸지 못했습니다. "
                        + "암호가 걸렸거나 Office가 무언가를 묻고 있을 수 있습니다. Office에서 열어 확인해 주세요.");
            }
            String said = said(output);
            if (process.exitValue() != 0 || !Files.isRegularFile(target) || Files.size(target) == 0) {
                log.warn("Office could not print {} ({}): {}", fileName, program, said.strip());
                throw new InvalidEditException(reason(said, program));
            }
            return Files.readAllBytes(target);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InvalidEditException("문서를 PDF로 바꾸다가 멈췄습니다.");
        } finally {
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            } catch (IOException e) {
                // a temporary folder left behind
            }
        }
    }

    /**
     * A CSV file as Excel reads it rightly: Excel takes a CSV without a byte order mark for the PC's own code page,
     * so one saved as UTF-8 (by a web site, a database) shows its Korean as broken letters. Such a file - valid
     * UTF-8 with letters beyond ASCII, no mark - is given the mark; any other file is left as it is.
     */
    static byte[] forExcel(byte[] content, String fileName) {
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            return content;
        }
        boolean marked = content.length >= 3 && (content[0] & 0xff) == 0xef && (content[1] & 0xff) == 0xbb
                && (content[2] & 0xff) == 0xbf;
        boolean plain = true;
        for (byte b : content) {
            if (b < 0) {
                plain = false;
                break;
            }
        }
        if (marked || plain) {
            return content;
        }
        try {
            StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(content));
        } catch (java.nio.charset.CharacterCodingException notUtf8) {
            return content; // the PC's code page (CP949): Excel reads it as it is
        }
        byte[] out = new byte[content.length + 3];
        out[0] = (byte) 0xef;
        out[1] = (byte) 0xbb;
        out[2] = (byte) 0xbf;
        System.arraycopy(content, 0, out, 3, content.length);
        return out;
    }

    /** What the script wrote, as far as it matters (its first 4000 bytes); empty when it wrote nothing. */
    static String said(Path output) {
        try (java.io.InputStream in = Files.newInputStream(output)) {
            return new String(in.readNBytes(4000), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Ends the Office program whose process number the script wrote down - only one the script itself started, so
     * a Word or PowerPoint the user has open is never touched. Nothing happens when no number was written.
     */
    static void endOffice(Path started) {
        try {
            long pid = Long.parseLong(Files.readString(started).strip());
            ProcessHandle.of(pid).ifPresent(office -> {
                String command = office.info().command().orElse("").toLowerCase(Locale.ROOT);
                if (command.endsWith("winword.exe") || command.endsWith("excel.exe") || command.endsWith("powerpnt.exe")) {
                    office.destroyForcibly();
                    log.warn("Ended the Office program left waiting (pid {})", pid);
                }
            });
        } catch (IOException | RuntimeException e) {
            // no number written: the script had not started Office yet, or it used one already open
        }
    }

    /** Why Office could not print it, said for the user. */
    static String reason(String said, String program) {
        String app = switch (program) {
            case "word" -> "Word";
            case "excel" -> "Excel";
            default -> "PowerPoint";
        };
        // 80040154: the program is not registered - Office (or that part of it) is not installed.
        if (said.contains("80040154") || said.toLowerCase(Locale.ROOT).contains("class not registered")) {
            // 엑셀 ends in a consonant: 이; 워드, 파워포인트: 가.
            return "이 PC에 MS " + app + ("excel".equals(program) ? "이" : "가") + " 설치되어 있지 않아 열 수 없습니다.";
        }
        if (said.toLowerCase(Locale.ROOT).contains("password") || said.contains("암호")) {
            return "암호가 걸린 Office 문서는 열 수 없습니다. " + app + "에서 암호를 풀어 저장한 뒤 열어 주세요.";
        }
        return "MS " + app + "로 이 문서를 PDF로 바꾸지 못했습니다. 파일이 손상되었거나 " + app + "에서 열리지 않는 문서일 수 있습니다.";
    }

    /**
     * Opens the document read-only in Office, without showing it or asking anything, prints it to PDF and quits
     * Office.
     * <ul>
     * <li>Macros are switched off first (AutomationSecurity 3, msoAutomationSecurityForceDisable): a program driving
     * Office otherwise runs a document's macros without asking.</li>
     * <li>A wrong password is given for a protected file, so Office fails at once instead of asking.</li>
     * <li>The process number of the Office program the script started is written down (the one that was not
     * running before), so one left waiting can be ended. PowerPoint runs once per PC: when the user already has it
     * open, that one is used and left open.</li>
     * </ul>
     */
    static final String SCRIPT = String.join("\n", List.of(
            "$ErrorActionPreference = 'Stop'",
            "$src = $args[0]; $dst = $args[1]; $kind = $args[2]; $pidFile = $args[3]",
            "$missing = [Type]::Missing",
            "$exe = @{ word = 'WINWORD'; excel = 'EXCEL'; powerpoint = 'POWERPNT' }[$kind]",
            "$before = @(Get-Process -Name $exe -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })",
            "function Note-Started {",
            "  $new = @(Get-Process -Name $exe -ErrorAction SilentlyContinue | Where-Object { $before -notcontains $_.Id })",
            "  if ($new.Count -ge 1) { Set-Content -Path $pidFile -Value $new[0].Id -Encoding ASCII }",
            "  return $new.Count -ge 1",
            "}",
            "switch ($kind) {",
            "  'word' {",
            "    $app = New-Object -ComObject Word.Application",
            "    [void](Note-Started)",
            "    $app.Visible = $false; $app.DisplayAlerts = 0; $app.AutomationSecurity = 3",
            "    try {",
            "      $doc = $app.Documents.Open($src, $false, $true, $false, '__wintech_no_password__')",
            "      $doc.ExportAsFixedFormat($dst, 17)",
            "      $doc.Close($false)",
            "    } finally { $app.Quit() }",
            "  }",
            "  'excel' {",
            "    $app = New-Object -ComObject Excel.Application",
            "    [void](Note-Started)",
            "    $app.Visible = $false; $app.DisplayAlerts = $false; $app.AskToUpdateLinks = $false",
            "    $app.AutomationSecurity = 3",
            "    try {",
            "      $book = $app.Workbooks.Open($src, 0, $true, $missing, '__wintech_no_password__')",
            "      $book.ExportAsFixedFormat(0, $dst)",
            "      $book.Close($false)",
            "    } finally { $app.Quit() }",
            "  }",
            "  'powerpoint' {",
            "    $app = New-Object -ComObject PowerPoint.Application",
            "    $mine = Note-Started",
            "    $app.AutomationSecurity = 3",
            "    try {",
            "      # 'file::password::' gives a password without asking: a protected file fails at once.",
            "      try { $show = $app.Presentations.Open($src + '::__wintech_no_password__::', -1, 0, 0) }",
            "      catch {",
            "        if ($_.Exception.Message -match 'password|암호') { throw }",
            "        $show = $app.Presentations.Open($src, -1, 0, 0)",
            "      }",
            "      $show.SaveAs($dst, 32)",
            "      $show.Close()",
            "    } finally { if ($mine) { $app.Quit() } }",
            "  }",
            "}",
            "[System.GC]::Collect()",
            "exit 0"));
}
