// javac --add-modules jdk.incubator.vector JsonArrayToJsonLinesStreamingSIMD.java
// java  --add-modules jdk.incubator.vector JsonArrayToJsonLinesStreamingSIMD
import java.io.*;
import jdk.incubator.vector.*;

public final class JsonArrayToJsonLinesStreamingSIMD {

    private static final byte QUOTE  = (byte) '"';
    private static final byte BSLASH = (byte) '\\';
    private static final byte LBRACK = (byte) '[';
    private static final byte RBRACK = (byte) ']';
    private static final byte LBRACE = (byte) '{';
    private static final byte RBRACE = (byte) '}';
    private static final byte COMMA  = (byte) ',';
    private static final byte NL     = (byte) '\n';

    private static boolean isWs(byte b) {
        return b == ' ' || b == '\n' || b == '\r' || b == '\t';
    }

    private static final VectorSpecies<Byte> S = bestSpecies();
    private static VectorSpecies<Byte> bestSpecies() {
        if (ByteVector.SPECIES_256.length() <= ByteVector.SPECIES_MAX.length()) return ByteVector.SPECIES_256;
        if (ByteVector.SPECIES_128.length() <= ByteVector.SPECIES_MAX.length()) return ByteVector.SPECIES_128;
        return ByteVector.SPECIES_64;
    }

    /**
     * Streaming JSON array → JSON Lines (with SIMD skip).
     */
    public static byte[] arrayToJsonLines(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        final int BUFSZ = 8192;
        byte[] buf = new byte[BUFSZ];

        boolean inString = false;
        int depth = 0;
        boolean seenArrayStart = false;
        boolean done = false;

        ByteArrayOutputStream elem = new ByteArrayOutputStream();

        int read;
        while (!done && (read = in.read(buf)) != -1) {
            int p = 0;
            while (p < read) {
                if (!seenArrayStart) {
                    while (p < read && isWs(buf[p])) p++;
                    if (p < read && buf[p] == LBRACK) {
                        seenArrayStart = true;
                        p++;
                        continue;
                    }
                    if (p < read) throw new IllegalArgumentException("Expected '[' at start");
                    break;
                }

                if (inString) {
                    // find next " or end of buffer
                    int next = nextInteresting(buf, p, read);
                    if (next < 0) next = read;
                    while (p < next) elem.write(buf[p++]); // bulk copy boring bytes
                    if (p >= read) break;
                    byte c = buf[p++];
                    elem.write(c);
                    if (c == QUOTE) {
                        int backslashes = countBackslashes(elem);
                        if ((backslashes & 1) == 0) inString = false;
                    }
                    continue;
                }

                // outside string
                int next = nextInteresting(buf, p, read);
                if (next < 0) next = read;
                while (p < next) {
                    byte c = buf[p++];
                    if (!isWs(c) || elem.size() > 0) {
                        elem.write(c);
                    }
                }
                if (p >= read) break;

                byte c = buf[p++];
                if (c == QUOTE) {
                    inString = true;
                    elem.write(c);
                    continue;
                }
                if (c == LBRACE || c == LBRACK) {
                    depth++;
                    elem.write(c);
                    continue;
                }
                if (c == RBRACE || c == RBRACK) {
                    if (c == RBRACK && depth == 0) {
                        flushElem(out, elem);
                        done = true;
                        continue;
                    }
                    depth--;
                    elem.write(c);
                    continue;
                }
                if (c == COMMA && depth == 0) {
                    flushElem(out, elem);
                    continue;
                }
                elem.write(c);
            }
        }

        if (!done) throw new IllegalArgumentException("Unexpected EOF (no closing ']')");
        return out.toByteArray();
    }

    /* ---- helpers ---- */

    private static void flushElem(ByteArrayOutputStream out, ByteArrayOutputStream elem) throws IOException {
        byte[] arr = elem.toByteArray();
        int from = 0, to = arr.length;
        while (from < to && isWs(arr[from])) from++;
        while (to > from && isWs(arr[to - 1])) to--;
        if (to > from) {
            out.write(arr, from, to - from);
            out.write(NL);
        }
        elem.reset();
    }

    private static int countBackslashes(ByteArrayOutputStream elem) {
        byte[] arr = elem.toByteArray();
        int i = arr.length - 2; // check chars before last written
        int count = 0;
        while (i >= 0 && arr[i] == BSLASH) {
            count++;
            i--;
        }
        return count;
    }

    // SIMD search for next "interesting" char
    private static int nextInteresting(byte[] a, int pos, int end) {
        int i = pos;
        int upper = end - (end - i) % S.length();
        final byte q = QUOTE, bs = BSLASH, cm = COMMA, lb = LBRACK, rb = RBRACK, lc = LBRACE, rc = RBRACE;
        while (i < upper) {
            var v = ByteVector.fromArray(S, a, i);
            var m = v.eq(q).or(v.eq(bs)).or(v.eq(cm))
                     .or(v.eq(lb)).or(v.eq(rb)).or(v.eq(lc)).or(v.eq(rc))
                     .toLong();
            if (m != 0L) {
                int lane = Long.numberOfTrailingZeros(m) >>> 3;
                return i + lane;
            }
            i += S.length();
        }
        for (; i < end; i++) {
            byte c = a[i];
            if (c == q || c == bs || c == cm || c == lb || c == rb || c == lc || c == rc) return i;
        }
        return -1;
    }

    /* ---- demo ---- */
    public static void main(String[] args) throws Exception {
        String json = "[{\"a\":1}, 2, \"x,y\", [3,4], null, {\"s\":\"a\\\"b\\\\c\"}]";
        InputStream in = new ByteArrayInputStream(json.getBytes());
        byte[] result = arrayToJsonLines(in);
        System.out.write(result);
    }
}