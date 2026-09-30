package dev.outrigger.document;

/**
 * How the {@code character} of an LSP position counts within a line, as
 * negotiated through {@code positionEncoding} during initialization.
 */
public enum PositionEncoding {
    UTF8,
    UTF16,
    UTF32;

    /** Parses an LSP {@code PositionEncodingKind}; LSP's default is UTF-16. */
    public static PositionEncoding fromLsp(String kind) {
        if (kind == null) {
            return UTF16;
        }
        return switch (kind) {
            case "utf-8" -> UTF8;
            case "utf-32" -> UTF32;
            default -> UTF16;
        };
    }

    /** Converts a position {@code character} into an index into the Java string {@code line}. */
    public int toCharIndex(String line, int character) {
        if (this == UTF16) {
            return Math.min(character, line.length());
        }
        int units = 0;
        int index = 0;
        while (index < line.length() && units < character) {
            int codePoint = line.codePointAt(index);
            units += unitsOf(codePoint);
            index += Character.charCount(codePoint);
        }
        return index;
    }

    /** Converts an index into the Java string {@code line} into a position {@code character}. */
    public int fromCharIndex(String line, int charIndex) {
        if (this == UTF16) {
            return charIndex;
        }
        int units = 0;
        int index = 0;
        int end = Math.min(charIndex, line.length());
        while (index < end) {
            int codePoint = line.codePointAt(index);
            units += unitsOf(codePoint);
            index += Character.charCount(codePoint);
        }
        return units;
    }

    private int unitsOf(int codePoint) {
        if (this == UTF32) {
            return 1;
        }
        if (codePoint < 0x80) {
            return 1;
        } else if (codePoint < 0x800) {
            return 2;
        } else if (codePoint < 0x10000) {
            return 3;
        }
        return 4;
    }
}
