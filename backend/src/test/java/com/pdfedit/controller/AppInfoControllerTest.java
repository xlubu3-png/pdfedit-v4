package com.pdfedit.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pdfedit.dto.AppInfo;
import com.pdfedit.dto.FontChoice;
import com.pdfedit.text.FontMatcher;
import com.pdfedit.text.SystemFonts;

class AppInfoControllerTest {

    @Test
    void reportsTheVersionAndHowManyFontsWereRead(@TempDir Path fontFolder) throws IOException {
        Files.writeString(fontFolder.resolve("not-a-font.ttf"), "unreadable, so not counted");

        AppInfo info = new AppInfoController("0.031", new FontMatcher(new SystemFonts(List.of(fontFolder)))).info();

        assertThat(info.version()).isEqualTo("0.031");
        assertThat(info.installedFonts()).isZero();
    }

    @Test
    void listsEachInstalledFamilyOnceWithTheKoreanOnesFirst() {
        List<FontChoice> fonts = new AppInfoController("0.031", new FontMatcher()).fonts();

        assertThat(fonts).extracting(FontChoice::name).doesNotHaveDuplicates().doesNotContain("");
        boolean otherSeen = false;
        for (FontChoice font : fonts) {
            otherSeen |= !font.korean();
            assertThat(otherSeen && font.korean()).as("%s listed after non-Korean fonts", font.name()).isFalse();
        }
    }

    @Test
    void countsTheFontsInstalledOnThisMachine() {
        AppInfo info = new AppInfoController("0.031", new FontMatcher()).info();

        assertThat(info.installedFonts()).isGreaterThanOrEqualTo(0);
    }
}
