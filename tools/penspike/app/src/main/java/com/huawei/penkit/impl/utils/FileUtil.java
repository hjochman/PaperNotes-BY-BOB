package com.huawei.penkit.impl.utils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Eigener Ersatz: liest eine Datei vollstaendig als UTF-8-Text. */
public final class FileUtil {
    private FileUtil() {
    }

    public static String readFileToString(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
