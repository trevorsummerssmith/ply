package org.moxie.ply;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * User: blangel
 * Date: 10/2/11
 * Time: 9:18 PM
 *
 * Defines the output mechanism within the {@literal Ply} application.
 * Support for colored VT-100 terminal output is controlled by the property {@literal color} within the
 * default context, {@literal ply}.
 */
public class Output {

    /**
     * A regex {@link java.util.regex.Pattern} paired with its corresponding output string.
     */
    private static final class TermCode {
        private final Pattern pattern;
        private final String output;
        private TermCode(Pattern pattern, String output) {
            this.pattern = pattern;
            this.output = output;
        }
    }

    /**
     * A mapping of easily identifiable words to a {@link TermCode} object for colored output.
     */
    private static final Map<String, TermCode> TERM_CODES = new HashMap<String, TermCode>();
    static {
        boolean withinTerminal = (System.getenv("TERM") != null);
        boolean colorDisabled = (System.getenv("ply.color") != null ? "false".equals(System.getenv("ply.color")) : false);
        boolean useColor = withinTerminal && !colorDisabled;
        // first place color values (in case call to Config tries to print, at least have something in
        // TERM_CODES with which to strip messages.
        TERM_CODES.put("ply", new TermCode(Pattern.compile("\\^ply\\^"), useColor ? "[\u001b[0;33mply\u001b[0m]" : "[ply]"));
        TERM_CODES.put("error", new TermCode(Pattern.compile("\\^error\\^"), useColor ? "[\u001b[1;31merr!\u001b[0m]" : "[err!]"));
        TERM_CODES.put("warn", new TermCode(Pattern.compile("\\^warn\\^"), useColor ? "[\u001b[1;33mwarn\u001b[0m]" : "[warn]"));
        TERM_CODES.put("info", new TermCode(Pattern.compile("\\^info\\^"), useColor ? "[\u001b[1;34minfo\u001b[0m]" : "[info]"));
        TERM_CODES.put("dbug", new TermCode(Pattern.compile("\\^dbug\\^"), useColor ? "[\u001b[1;30mdbug\u001b[0m]" : "[dbug]"));
        TERM_CODES.put("reset", new TermCode(Pattern.compile("\\^r\\^"), useColor ? "\u001b[0m" : ""));
        TERM_CODES.put("bold", new TermCode(Pattern.compile("\\^b\\^"), useColor ? "\u001b[1m" : ""));
        TERM_CODES.put("normal", new TermCode(Pattern.compile("\\^n\\^"), useColor ? "\u001b[2m" : ""));
        TERM_CODES.put("inverse", new TermCode(Pattern.compile("\\^i\\^"), useColor ? "\u001b[7m" : ""));
        TERM_CODES.put("black", new TermCode(Pattern.compile("\\^black\\^"), useColor ? "\u001b[1;30m" : ""));
        TERM_CODES.put("red", new TermCode(Pattern.compile("\\^red\\^"), useColor ? "\u001b[1;31m" : ""));
        TERM_CODES.put("green", new TermCode(Pattern.compile("\\^green\\^"), useColor ? "\u001b[1;32m" : ""));
        TERM_CODES.put("yellow", new TermCode(Pattern.compile("\\^yellow\\^"), useColor ? "\u001b[1;33m" : ""));
        TERM_CODES.put("blue", new TermCode(Pattern.compile("\\^blue\\^"), useColor ? "\u001b[1;34m" : ""));
        TERM_CODES.put("magenta", new TermCode(Pattern.compile("\\^magenta\\^"), useColor ? "\u001b[1;35m" : ""));
        TERM_CODES.put("cyan", new TermCode(Pattern.compile("\\^cyan\\^"), useColor ? "\u001b[1;36m" : ""));
        TERM_CODES.put("white", new TermCode(Pattern.compile("\\^white\\^"), useColor ? "\u001b[1;37m" : ""));
    }

    private static final AtomicReference<Boolean> warnLevel = new AtomicReference<Boolean>(true);
    private static final AtomicReference<Boolean> infoLevel = new AtomicReference<Boolean>(true);
    private static final AtomicReference<Boolean> dbugLevel = new AtomicReference<Boolean>(true);

    /**
     * Remaps the {@link #TERM_CODES} appropriately if the {@literal color} property is false.
     * Also figures out what log levels are available.
     * Requires property resolution and so is post-static initialization and package protected.
     * @param color false to unmap color terminal codes
     * @param logLevels the {@literal ply.log.levels} property value
     */
    static void init(boolean color, String logLevels) {
        if (!color) {
            TERM_CODES.put("ply", new TermCode(TERM_CODES.get("ply").pattern, "[ply]"));
            TERM_CODES.put("error", new TermCode(TERM_CODES.get("error").pattern, "[err!]"));
            TERM_CODES.put("warn", new TermCode(TERM_CODES.get("warn").pattern, "[warn]"));
            TERM_CODES.put("info", new TermCode(TERM_CODES.get("info").pattern, "[info]"));
            TERM_CODES.put("dbug", new TermCode(TERM_CODES.get("dbug").pattern, "[dbug]"));
            TERM_CODES.put("reset", new TermCode(TERM_CODES.get("reset").pattern, ""));
            TERM_CODES.put("bold", new TermCode(TERM_CODES.get("bold").pattern, ""));
            TERM_CODES.put("normal", new TermCode(TERM_CODES.get("normal").pattern, ""));
            TERM_CODES.put("inverse", new TermCode(TERM_CODES.get("inverse").pattern, ""));
            TERM_CODES.put("black", new TermCode(TERM_CODES.get("black").pattern, ""));
            TERM_CODES.put("red", new TermCode(TERM_CODES.get("red").pattern, ""));
            TERM_CODES.put("green", new TermCode(TERM_CODES.get("green").pattern, ""));
            TERM_CODES.put("yellow", new TermCode(TERM_CODES.get("yellow").pattern, ""));
            TERM_CODES.put("blue", new TermCode(TERM_CODES.get("blue").pattern, ""));
            TERM_CODES.put("magenta", new TermCode(TERM_CODES.get("magenta").pattern, ""));
            TERM_CODES.put("cyan", new TermCode(TERM_CODES.get("cyan").pattern, ""));
            TERM_CODES.put("white", new TermCode(TERM_CODES.get("white").pattern, ""));
        }
        if (!logLevels.contains("warn")) {
            warnLevel.set(false);
        }
        if (!logLevels.contains("info")) {
            infoLevel.set(false);
        }
        if (!logLevels.contains("debug") && !logLevels.contains("dbug")) {
            dbugLevel.set(false);
        }
    }

    public static void print(String message, Object ... args) {
        String formatted = String.format(message, args);
        // TODO - fix!  this case fails: ^cyan^warn^r^ if ^warn^ is evaluated first...really meant for ^cyan^ and ^r^
        // TODO - to be resolved
        for (String key : TERM_CODES.keySet()) {
            TermCode termCode = TERM_CODES.get(key);
            Matcher matcher = termCode.pattern.matcher(formatted);
            if (matcher.find()) {
                if (("warn".equals(key) && !warnLevel.get()) || ("info".equals(key) && !infoLevel.get())
                        || ("dbug".equals(key) && !dbugLevel.get())) {
                    // this is a log statement for a disabled log-level, skip.
                    return;
                }
                formatted = matcher.replaceAll(termCode.output);
            }
        }
        System.out.println(formatted);
    }

    public static void print(Throwable t) {
        Output.print("^error^ Message: ^i^^red^%s^r^", (t == null ? "" : t.getMessage()));
    }
}