// javac --add-modules jdk.incubator.vector JsonArrayToJsonLinesIterator.java
// java  --add-modules jdk.incubator.vector JsonArrayToJsonLinesIterator
import java.io.*;
import java.util.*;
import jdk.incubator.vector.*;

public final class JsonArrayToJsonLinesIterator {

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
     * Return an Iterable over byte[] elements (JSON Lines).
     */
    public static Iterable<byte[]> parseJsonArray(InputStream in) {
        return () -> new Iterator<>() {
            final int BUFSZ = 8192;
            final byte[] buf = new byte[BUFSZ];
            int read = 0, pos = 0;

            boolean inString = false;
            int depth = 0;
            boolean seenArrayStart = false;
            boolean done = false;

            ByteArrayOutputStream elem = new ByteArrayOutputStream();

            byte[] nextElem = null;

            @Override
            public boolean hasNext() {
                if (nextElem != null) return true;
                try {
                    fetchNext();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return nextElem != null;
            }

            @Override
            public byte[] next() {
                if (!hasNext()) throw new NoSuchElementException();
                byte[] res = nextElem;
                nextElem = null;
                return res;
            }

            private void fetchNext() throws IOException {
                if (done) return;

                while (true) {
                    if (pos >= read) {
                        read = in.read(buf);
                        pos = 0;
                        if (read == -1) {
                            if (!done) throw new IllegalArgumentException("Unexpected EOF");
                            return;
                        }
                    }
                    int p = pos;
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
                            int next = nextInteresting(buf, p, read);
                            if (next < 0) next = read;
                            elem.write(buf, p, next - p);
                            p = next;
                            if (p >= read) break;
                            byte c = buf[p++];
                            elem.write(c);
                            if (c == QUOTE) {
                                int bs = countBackslashes(elem);
                                if ((bs & 1) == 0) inString = false;
                            }
                            continue;
                        }

                        int next = nextInteresting(buf, p, read);
                        if (next < 0) next = read;
                        while (p < next) {
                            byte c = buf[p++];
                            if (!isWs(c) || elem.size() > 0) elem.write(c);
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
                                nextElem = finalizeElem(elem);
                                done = true;
                                pos = p;
                                return;
                            }
                            depth--;
                            elem.write(c);
                            continue;
                        }
                        if (c == COMMA && depth == 0) {
                            nextElem = finalizeElem(elem);
                            pos = p;
                            return;
                        }
                        elem.write(c);
                    }
                    pos = p;
                }
            }
        };
    }

    /* ---- helpers ---- */

    private static byte[] finalizeElem(ByteArrayOutputStream elem) {
        byte[] arr = elem.toByteArray();
        int from = 0, to = arr.length;
        while (from < to && isWs(arr[from])) from++;
        while (to > from && isWs(arr[to - 1])) to--;
        elem.reset();
        if (to > from) {
            byte[] out = new byte[to - from + 1];
            System.arraycopy(arr, from, out, 0, to - from);
            out[out.length - 1] = NL;
            return out;
        }
        return null; // empty
    }

    private static int countBackslashes(ByteArrayOutputStream elem) {
        byte[] arr = elem.toByteArray();
        int i = arr.length - 2;
        int count = 0;
        while (i >= 0 && arr[i] == BSLASH) {
            count++;
            i--;
        }
        return count;
    }

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

        for (byte[] elem : parseJsonArray(in)) {
            System.out.write(elem);
        }
    }
}