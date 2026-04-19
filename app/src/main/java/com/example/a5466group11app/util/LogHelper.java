package com.example.a5466group11app.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LogHelper {

    private LogHelper() {
    }

    public static String appendLog(String currentText, String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        return currentText + "[" + time + "] " + message + "\n";
    }

    public static String sanitizeCommand(String command) {
        return command.replace("\n", "\\n");
    }

    public static String resetLog() {
        return "Ready...\n";
    }
}