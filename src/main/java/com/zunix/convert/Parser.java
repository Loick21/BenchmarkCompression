// javac --add-modules jdk.incubator.vector JsonArrayToJsonLines.java
// java  --add-modules jdk.incubator.vector JsonArrayToJsonLines
import java.io.*;
import java.nio.charset.StandardCharsets;
import jdk.incubator.vector.*;

public final class JsonArrayToJsonLines {

    // Characters we treat as "interesting" during the scan.
    private static final byte QUOTE = (byte) '"';
    private static final byte BSLASH = (byte) '\\';
    private static final byte LBRACK = (byte) '[';
    private static final byte RBRACK = (byte) ']';
    private static final byte LBRACE = (byte) '{';
    private static final byte RBRACE = (byte) '}';
    private static final byte COMMA  = (byte) ',';

    // Whitespace: JSON allows space, tab, CR, LF.
    private static boolean isWs(byte b) {
        return b == ' ' || b == '\n' || b == '\r' || b == '\t';
    }

    // Vector species (prefer widest supported).
    private static final VectorSpecies<Byte> S = bestSpecies();

    private static VectorSpecies<Byte> bestSpecies() {
        for (VectorSpecies<Byte> cand : new VectorSpecies[]{ByteVector.SPECIES_256, ByteVector.SPECIES_128, ByteVector.SPECIES_64}) {
            if (cand.length() <= ByteVector.SPECIES_MAX.length()) return cand;
        }
        return ByteVector.SPECIES_64;
    }

    /**
     * Convert a JSON array (as String) into JSON Lines (elements separated by '\n').
     * Throws IllegalArgumentException on structural errors.
     */
    public static String arrayToJsonLines(String jsonArray) {
        byte[] in = jsonArray.getBytes(StandardCharsets.UTF_8);
        int n = in.length;

        int i = skipWsFwd(in, 0, n);
        if (i >= n || in[i] != LBRACK) {
            throw new IllegalArgumentException("Input must start with '[' (after optional whitespace).");
        }
        int end = skipWsBack(in, 0, n) - 1;
        if (end < 0 || in[end] != RBRACK) {
            throw new IllegalArgumentException("Input must end with ']' (before optional whitespace).");
        }

        // Empty array fast path: [] or [   ]
        int j = skipWsFwd(in, i + 1, end);
        if (j >= end) return ""; // zero elements => zero lines

        StringBuilder out = new StringBuilder(Math.max(32, n)); // rough upper bound

        int elemStart = j;
        int depth = 0;
        boolean inString = false;

        int p = j;
        while (p <= end) {
            // SIMD-accelerated hop to next interesting byte
            int next = nextInteresting(in, p, end + 1);
            if (next < 0) next = end; // nothing else; will be handled below char-by-char

            // Process bytes from p to next-1 as plain; nothing to do but advance
            p = next;
            if (p > end) break;

            byte c = in[p];

            if (inString) {
                if (c == QUOTE) {
                    // Toggle string state if quote isn't escaped (odd # of preceding backslashes)
                    int bcount = backslashRun(in, p - 1);
                    if ((bcount & 1) == 0) inString = false;
                }
                // Inside string, we ignore all structure
                p++;
                continue;
            }

            // Not in string: handle structure
            if (c == QUOTE) {
                inString = true;
                p++;
                continue;
            }

            // Depth updates (branchless-ish using arithmetic)
            // We only adjust depth for braces/brackets outside strings
            if (c == LBRACE || c == LBRACK) {
                depth++;
                p++;
                continue;
            }
            if (c == RBRACE || c == RBRACK) {
                // If it's a closing ']' at top level, that ends the last element
                if (c == RBRACK && depth == 0) {
                    // flush last element [elemStart, p)
                    int endElem = skipWsBack(in, elemStart, p);
                    appendLine(out, in, elemStart, endElem);
                    p++; // consume ']'
                    break;
                }
                depth--;
                p++;
                continue;
            }

            if (c == COMMA && depth == 0) {
                // Element boundary (top-level comma inside the array)
                int endElem = skipWsBack(in, elemStart, p);
                appendLine(out, in, elemStart, endElem);
                p++; // consume ','
                // next elem start = first non-ws after comma
                elemStart = skipWsFwd(in, p, end);
                p = elemStart;
                continue;
            }

            // Other bytes (including whitespace, digits, letters) just advance
            p++;
        }

        // Validate we consumed the array close
        int tail = skipWsFwd(in, p, n);
        if (tail < n) {
            // allow trailing whitespace only
            if (skipWsFwd(in, tail, n) != n) {
                throw new IllegalArgumentException("Trailing garbage after closing ']'.");
            }
        }

        // Drop last '\n' if present
        if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    /* -------------------- helpers -------------------- */

    private static int skipWsFwd(byte[] a, int i, int limit) {
        while (i < limit && isWs(a[i])) i++;
        return i;
    }

    private static int skipWsBack(byte[] a, int start, int i) {
        // returns index of last non-ws + 1 (exclusive end)
        int r = i;
        while (r > start && isWs(a[r - 1])) r--;
        return r;
    }

    private static void appendLine(StringBuilder out, byte[] src, int from, int toExclusive) {
        if (toExclusive <= from) return; // ignore zero-length (shouldn't happen for valid JSON)
        out.append(new String(src, from, toExclusive - from, StandardCharsets.UTF_8)).append('\n');
    }

    // Count contiguous '\' ending at idx (idx points to last char BEFORE current quote)
    private static int backslashRun(byte[] a, int idx) {
        int c = 0;
        while (idx >= 0 && a[idx] == BSLASH) {
            c++;
            idx--;
        }
        return c;
    }

    /**
     * SIMD hop: find next index in [pos, end) where byte ∈ {"\\", "\"", ",", "[", "]", "{", "}"}.
     * Returns -1 if none.
     */
    private static int nextInteresting(byte[] a, int pos, int end) {
        int i = pos;
        int upper = end - (end - i) % S.length(); // full-vector boundary
        // Vector compare against each target and OR masks
        final byte q = QUOTE, bs = BSLASH, cm = COMMA, lb = LBRACK, rb = RBRACK, lc = LBRACE, rc = RBRACE;

        while (i < upper) {
            var v = ByteVector.fromArray(S, a, i);
            var m = v.eq((byte) q)
                     .or(v.eq((byte) bs))
                     .or(v.eq((byte) cm))
                     .or(v.eq((byte) lb))
                     .or(v.eq((byte) rb))
                     .or(v.eq((byte) lc))
                     .or(v.eq((byte) rc))
                     .toLong();

            if (m != 0L) {
                // find first matching lane
                int lane = Long.numberOfTrailingZeros(m) >>> 3; // /8 for bytes
                return i + lane;
            }
            i += S.length();
        }

        // scalar tail
        for (; i < end; i++) {
            byte c = a[i];
            if (c == q || c == bs || c == cm || c == lb || c == rb || c == lc || c == rc) {
                return i;
            }
        }
        return -1;
    }

    /* -------------------- simple demo -------------------- */

    public static void main(String[] args) throws IOException {
        // Read all of stdin -> convert -> write to stdout
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        InputStream in = System.in;
        while ((r = in.read(buf)) != -1) baos.write(buf, 0, r);
        String input = baos.toString(StandardCharsets.UTF_8);
        String out = arrayToJsonLines(input);
        System.out.print(out);
    }
}