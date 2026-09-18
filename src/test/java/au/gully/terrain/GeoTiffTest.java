package au.gully.terrain;

import au.gully.hexagons.Albers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The GeoTIFF reader against files written here, byte by byte, in the shapes the national rasters
 * come in: stripped and tiled, little- and big-endian, float and integer, uncompressed and Deflate
 * with the horizontal predictor, geographic and Australian Albers, with a nodata value.
 */
class GeoTiffTest {

    @TempDir
    Path dir;

    /**
     * A geographic raster 10 × 8 cells of 0.25°, whose value is 100·row + col, over the box
     * 138–140.5 E, 34–36 S (north edge at 34 S, PixelIsArea).
     */
    @Test
    void readsAStrippedFloatRasterInLatLon() throws IOException {
        Path file = dir.resolve("dem.tif");
        Files.write(file, tiff(10, 8, 32, 3, false, ByteOrder.LITTLE_ENDIAN, 1, 1, false, -9999, -34, 138, 0.25, null));
        try (GeoTiff t = GeoTiff.open(file)) {
            assertThat(t.width()).isEqualTo(10);
            assertThat(t.height()).isEqualTo(8);
            // Cell (col 2, row 3): lon 138.5–138.75, lat -34.75 to -35.
            assertThat(t.sample(-34.8, 138.6)).isEqualTo(302);
            assertThat(t.sample(-34.01, 138.01)).isEqualTo(0);
            assertThat(t.sample(-35.99, 140.49)).isEqualTo(709);
            assertThat(t.sample(-33.9, 138.6)).as("north of the raster").isNaN();
            assertThat(t.sample(-34.8, 141)).as("east of the raster").isNaN();
        }
    }

    @Test
    void honoursNodataAndBigEndianIntegers() throws IOException {
        Path file = dir.resolve("nodata.tif");
        // 16-bit unsigned, big-endian, cell (1,1) written as the nodata value.
        Files.write(file, tiff(4, 4, 16, 1, false, ByteOrder.BIG_ENDIAN, 1, 1, false, 101, -34, 138, 0.5, null));
        try (GeoTiff t = GeoTiff.open(file)) {
            assertThat(t.sample(-34.25, 138.25)).isEqualTo(0);
            assertThat(t.sample(-34.75, 138.75)).as("value 101 is the nodata value").isNaN();
            assertThat(t.sample(-35.75, 139.75)).isEqualTo(303);
        }
    }

    @Test
    void readsATiledDeflatedRasterWithThePredictorInAlbers() throws IOException {
        Path file = dir.resolve("landuse.tif");
        // Albers, 50 m cells, 64 × 64, tiled 32 × 32, 8-bit with predictor 2 and Deflate; the origin at
        // the projected position of -34.9, 138.6.
        double[] origin = Albers.forward(-34.9, 138.6);
        byte[] bytes = tiff(64, 64, 8, 1, true, ByteOrder.LITTLE_ENDIAN, 8, 2, true, -1, origin[1], origin[0], 50, 3577);
        Files.write(file, bytes);
        try (GeoTiff t = GeoTiff.open(file)) {
            assertThat(t.cellMetres()).isEqualTo(50);
            // Cell (col 40, row 50): 50 rows south, 40 cols east of the origin. Value 100·50 + 40 = 5040 → in 8 bits, 5040 & 0xFF = 176.
            double[] ll = Albers.inverse(origin[0] + 40 * 50 + 25, origin[1] - 50 * 50 - 25);
            assertThat(t.sample(ll[0], ll[1])).isEqualTo(5040 & 0xFF);
            double[] first = Albers.inverse(origin[0] + 25, origin[1] - 25);
            assertThat(t.sample(first[0], first[1])).isEqualTo(0);
        }
    }

    // ---------------------------------------------------------------- a TIFF writer, just enough

    /**
     * Writes a single-band TIFF whose sample at (col, row) is {@code 100·row + col} (masked to the
     * sample width), except (1,1) which carries {@code nodata} when one is given.
     */
    static byte[] tiff(int width, int height, int bits, int sampleFormat, boolean tiled, ByteOrder order,
                       int compression, int predictor, boolean useTiles32, double nodata,
                       double originYorLat, double originXorLon, double scale, Integer epsg) throws IOException {
        int bytesPer = bits / 8;
        int tileW = tiled ? 32 : width;
        int tileH = tiled ? 32 : height;
        int across = (width + tileW - 1) / tileW;
        int down = (height + tileH - 1) / tileH;
        List<byte[]> chunks = new ArrayList<>();
        for (int ty = 0; ty < down; ty++) {
            for (int tx = 0; tx < across; tx++) {
                ByteBuffer b = ByteBuffer.allocate(tileW * tileH * bytesPer).order(order);
                for (int r = 0; r < tileH; r++) {
                    int row = ty * tileH + r;
                    for (int c = 0; c < tileW; c++) {
                        int col = tx * tileW + c;
                        double v = (row < height && col < width) ? 100.0 * row + col : 0;
                        if (row == 1 && col == 1 && nodata != -1) {
                            v = nodata;
                        }
                        switch (bits) {
                            case 8 -> b.put((byte) ((int) v & 0xFF));
                            case 16 -> b.putShort((short) ((int) v & 0xFFFF));
                            default -> {
                                if (sampleFormat == 3) b.putFloat((float) v);
                                else b.putInt((int) v);
                            }
                        }
                    }
                }
                byte[] raw = b.array();
                if (predictor == 2) {
                    int rowBytes = tileW * bytesPer;
                    for (int r = 0; r < tileH; r++) {
                        for (int i = rowBytes - 1; i >= bytesPer; i--) {
                            raw[r * rowBytes + i] -= raw[r * rowBytes + i - bytesPer];
                        }
                    }
                }
                chunks.add(compression == 8 ? deflate(raw) : raw);
            }
        }
        // Layout: header (8), IFD, then the tag arrays, then the chunks.
        List<int[]> tags = new ArrayList<>(); // tag, type, count, value-or-offset placeholder index
        ByteArrayOutputStream extra = new ByteArrayOutputStream();
        int ifdEntries = (tiled ? 15 : 14) + (nodata != -1 ? 1 : 0) + (predictor == 2 ? 1 : 0);
        int ifdSize = 2 + ifdEntries * 12 + 4;
        int extraStart = 8 + ifdSize;
        // Offsets of chunks come after the extra block; compute extra first.
        ByteBuffer scaleBuf = ByteBuffer.allocate(24).order(order).putDouble(scale).putDouble(scale).putDouble(0);
        int scaleOff = extraStart + extra.size();
        extra.write(scaleBuf.array());
        ByteBuffer tieBuf = ByteBuffer.allocate(48).order(order).putDouble(0).putDouble(0).putDouble(0)
                .putDouble(originXorLon).putDouble(originYorLat).putDouble(0);
        int tieOff = extraStart + extra.size();
        extra.write(tieBuf.array());
        // GeoKeys: model type (1 projected / 2 geographic), raster type area, projected CS.
        int keyCount = epsg != null ? 3 : 2;
        ByteBuffer keys = ByteBuffer.allocate((4 + keyCount * 4) * 2).order(order);
        keys.putShort((short) 1).putShort((short) 1).putShort((short) 0).putShort((short) keyCount);
        keys.putShort((short) 1024).putShort((short) 0).putShort((short) 1).putShort((short) (epsg != null ? 1 : 2));
        keys.putShort((short) 1025).putShort((short) 0).putShort((short) 1).putShort((short) 1);
        if (epsg != null) {
            keys.putShort((short) 3072).putShort((short) 0).putShort((short) 1).putShort(epsg.shortValue());
        }
        int keysOff = extraStart + extra.size();
        extra.write(keys.array());
        String nd = nodata == -1 ? null : String.valueOf((int) nodata) + "\0";
        int ndOff = extraStart + extra.size();
        if (nd != null) {
            extra.write(nd.getBytes());
        }
        int offsetsOff = extraStart + extra.size();
        int chunkStart = offsetsOff + chunks.size() * 8;
        ByteBuffer offsets = ByteBuffer.allocate(chunks.size() * 4).order(order);
        ByteBuffer counts = ByteBuffer.allocate(chunks.size() * 4).order(order);
        int pos = chunkStart;
        for (byte[] c : chunks) {
            offsets.putInt(pos);
            counts.putInt(c.length);
            pos += c.length;
        }
        extra.write(offsets.array());
        extra.write(counts.array());

        ByteBuffer out = ByteBuffer.allocate(chunkStart + (pos - chunkStart)).order(order);
        out.putShort(order == ByteOrder.LITTLE_ENDIAN ? (short) 0x4949 : (short) 0x4D4D);
        out.putShort((short) 42);
        out.putInt(8);
        out.putShort((short) ifdEntries);
        entry(out, 256, 4, 1, width);
        entry(out, 257, 4, 1, height);
        entry(out, 258, 3, 1, bits);
        entry(out, 259, 3, 1, compression);
        entry(out, 262, 3, 1, 1);
        if (tiled) {
            entry(out, 322, 4, 1, tileW);
            entry(out, 323, 4, 1, tileH);
            entry(out, 324, 4, chunks.size(), chunks.size() == 1 ? chunkStart : offsetsOff);
            entry(out, 325, 4, chunks.size(), chunks.size() == 1 ? chunks.getFirst().length : offsetsOff + chunks.size() * 4);
        } else {
            entry(out, 273, 4, chunks.size(), chunks.size() == 1 ? chunkStart : offsetsOff);
            entry(out, 277, 3, 1, 1);
            entry(out, 278, 4, 1, height);
            entry(out, 279, 4, chunks.size(), chunks.size() == 1 ? chunks.getFirst().length : offsetsOff + chunks.size() * 4);
        }
        if (tiled) {
            entry(out, 277, 3, 1, 1);
            entry(out, 284, 3, 1, 1);
        } else {
            entry(out, 284, 3, 1, 1);
        }
        if (predictor == 2) {
            entry(out, 317, 3, 1, 2);
        }
        entry(out, 339, 3, 1, sampleFormat);
        entry(out, 33550, 12, 3, scaleOff);
        entry(out, 33922, 12, 6, tieOff);
        entry(out, 34735, 3, 4 + keyCount * 4, keysOff);
        if (nd != null) {
            // Four bytes or fewer are stored in the entry itself, as the specification requires.
            if (nd.length() <= 4) {
                out.putShort((short) 42113).putShort((short) 2).putInt(nd.length());
                byte[] inline = new byte[4];
                System.arraycopy(nd.getBytes(), 0, inline, 0, nd.length());
                out.put(inline);
            } else {
                entry(out, 42113, 2, nd.length(), ndOff);
            }
        }
        out.putInt(0);
        out.put(extra.toByteArray());
        for (byte[] c : chunks) {
            out.put(c);
        }
        return out.array();
    }

    private static void entry(ByteBuffer out, int tag, int type, int count, int value) {
        out.putShort((short) tag).putShort((short) type).putInt(count);
        if (type == 3 && count == 1) {
            out.putShort((short) value).putShort((short) 0);
        } else {
            out.putInt(value);
        }
    }

    private static byte[] deflate(byte[] raw) {
        Deflater d = new Deflater();
        d.setInput(raw);
        d.finish();
        byte[] buf = new byte[raw.length + 64];
        int n = d.deflate(buf);
        d.end();
        byte[] out = new byte[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }
}
