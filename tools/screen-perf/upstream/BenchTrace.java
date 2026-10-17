import java.io.*;
import java.util.*;

/**
 * Benchmark trace: measures IME reactions frame by frame.
 *
 * Input is the down-scaled stacked stream (see stack.js):
 *
 *   row 0..3    keyboard rows 0..3, each averaged to a single scanline
 *   row 4..6    composition / candidate strip
 *   row 7..22   note body (the document itself)
 *
 * Two kinds of signal are emitted per frame:
 *
 *   vsPrev  how many pixels changed since the previous frame. screenrecord only
 *           emits a frame when something moves, so this isolates the reaction
 *           that just happened - it is the onset/edge detector.
 *   vsRef   how many pixels differ from the first frame, i.e. persistent state
 *           (text that has been committed stays different).
 *
 * For each keyboard row the horizontal extent of the change is reported, which
 * identifies which key reacted (key centres are known from keymap.json).
 *
 * usage: BenchTrace <raw> <w> <tsFile> [thr]
 */
public class BenchTrace {

    static final int NKEY = 4;          // keyboard rows
    static final int BARH = 3;          // candidate strip lines (rows 4..6)
    static final int EDH = 16;          // editor lines (rows 7..22)
    static final int H = NKEY + BARH + EDH;

    static int W;
    static int thr;

    public static void main(String[] args) throws Exception {
        String file = args[0];
        W = Integer.parseInt(args[1]);
        String tsFile = args[2];
        thr = args.length > 3 ? Integer.parseInt(args[3]) : 40;

        double[] ts;
        {
            List<Double> l = new ArrayList<>();
            for (String s : java.nio.file.Files.readAllLines(java.nio.file.Paths.get(tsFile)))
                if (!s.isBlank()) l.add(Double.parseDouble(s.trim()));
            ts = new double[l.size()];
            for (int i = 0; i < ts.length; i++) ts[i] = l.get(i);
        }

        long fb = (long) W * H * 3;
        int n = (int) (new File(file).length() / fb);
        byte[] ref = read(file, 0, fb);
        byte[] prev = read(file, 0, fb);
        byte[] cur = new byte[(int) fb];

        StringBuilder h = new StringBuilder("i\tt");
        for (int r = 0; r < NKEY; r++) h.append("\tk").append(r).append("_p\tk").append(r).append("_x0\tk").append(r).append("_x1");
        h.append("\tbar_p\tbar_r\ted_p\ted_r");
        System.out.println(h);

        for (int i = 1; i < n; i++) {
            read(file, i, fb, cur);
            StringBuilder sb = new StringBuilder();
            sb.append(i).append('\t').append(String.format("%.4f", i < ts.length ? ts[i] : -1));
            for (int r = 0; r < NKEY; r++) {
                int[] e = rowDeltaExtent(prev, cur, r);
                sb.append('\t').append(e[0]).append('\t').append(e[1]).append('\t').append(e[2]);
            }
            sb.append('\t').append(regionDelta(prev, cur, NKEY, NKEY + BARH));
            sb.append('\t').append(regionDelta(ref, cur, NKEY, NKEY + BARH));
            sb.append('\t').append(regionDelta(prev, cur, NKEY + BARH, H));
            sb.append('\t').append(regionDelta(ref, cur, NKEY + BARH, H));
            System.out.println(sb);
            byte[] t = prev; prev = cur; cur = t;
        }
        System.err.println("frames=" + n);
    }

    /** returns {changedPixels, firstX, lastX} for one keyboard row */
    static int[] rowDeltaExtent(byte[] a, byte[] b, int row) {
        int off = row * W * 3;
        int c = 0, x0 = -1, x1 = -1;
        for (int x = 0; x < W; x++) {
            int p = off + x * 3;
            int d = Math.abs((a[p] & 0xFF) - (b[p] & 0xFF))
                  + Math.abs((a[p + 1] & 0xFF) - (b[p + 1] & 0xFF))
                  + Math.abs((a[p + 2] & 0xFF) - (b[p + 2] & 0xFF));
            if (d >= thr) { c++; if (x0 < 0) x0 = x; x1 = x; }
        }
        return new int[]{c, x0, x1};
    }

    static long regionDelta(byte[] a, byte[] b, int y0, int y1) {
        long c = 0;
        for (int y = y0; y < y1; y++) {
            int off = y * W * 3;
            for (int x = 0; x < W; x++) {
                int p = off + x * 3;
                int d = Math.abs((a[p] & 0xFF) - (b[p] & 0xFF))
                      + Math.abs((a[p + 1] & 0xFF) - (b[p + 1] & 0xFF))
                      + Math.abs((a[p + 2] & 0xFF) - (b[p + 2] & 0xFF));
                if (d >= thr) c++;
            }
        }
        return c;
    }

    static byte[] read(String f, int idx, long fb) throws IOException {
        byte[] o = new byte[(int) fb]; read(f, idx, fb, o); return o;
    }
    static void read(String f, int idx, long fb, byte[] o) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) { r.seek(idx * fb); r.readFully(o); }
    }
}
