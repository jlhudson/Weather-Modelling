package au.gully.terrain;

import au.gully.hexagons.Albers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A single-band GeoTIFF on the service's own volume, read one sample at a time (docs/06 items 17 and
 * 19). Written here rather than pulled in as a library because the whole need is "the value of one
 * cell at a latitude and longitude", and a GIS stack for that is a disk mount, a licence page and
 * forty megabytes of jar for one method.
 * <p>
 * What it reads: classic and BigTIFF, either byte order, stripped or tiled, one sample per pixel (the
 * first band of a chunky multi-band file), 8-, 16-, 32- and 64-bit integers and 32- and 64-bit
 * floats, uncompressed, Deflate, LZW or PackBits, with the horizontal and floating-point predictors.
 * Georeferencing from the pixel scale and tie point tags (or the model transformation), in either a
 * geographic lat/lon system or Australian Albers (EPSG:3577) — which between them are how Geoscience
 * Australia and ABARES publish their national rasters. A GDAL nodata tag is honoured.
 * <p>
 * The file is read positionally, never mapped, so a five-gigabyte land-use raster costs no address
 * space, and the last few decoded tiles are kept so the thousands of samples a hexagon overlay takes
 * hit the disk a handful of times. A raster fetched for one hexagon - a few kilobytes from a WCS -
 * is read the same way from memory ({ #of}).
 */
public final class GeoTiff implements AutoCloseable {

    private static final int TAG_WIDTH = 256, TAG_LENGTH = 257, TAG_BITS = 258, TAG_COMPRESSION = 259,
            TAG_STRIP_OFFSETS = 273, TAG_SAMPLES_PER_PIXEL = 277, TAG_ROWS_PER_STRIP = 278, TAG_STRIP_BYTES = 279,
            TAG_PLANAR = 284, TAG_PREDICTOR = 317, TAG_TILE_WIDTH = 322, TAG_TILE_LENGTH = 323,
            TAG_TILE_OFFSETS = 324, TAG_TILE_BYTES = 325, TAG_SAMPLE_FORMAT = 339,
            TAG_MODEL_PIXEL_SCALE = 33550, TAG_MODEL_TIEPOINT = 33922, TAG_MODEL_TRANSFORMATION = 34264,
            TAG_GEO_KEYS = 34735, TAG_GDAL_NODATA = 42113;

    private static final int COMPRESSION_NONE = 1, COMPRESSION_LZW = 5, COMPRESSION_DEFLATE = 8,
            COMPRESSION_DEFLATE_OLD = 32946, COMPRESSION_PACKBITS = 32773;

    private static final int GEO_MODEL_TYPE = 1024, GEO_RASTER_TYPE = 1025, GEO_PROJECTED_CS = 3072;
    private static final int MODEL_PROJECTED = 1, MODEL_GEOGRAPHIC = 2;
    private static final int RASTER_PIXEL_IS_POINT = 2;
    private static final int EPSG_AUSTRALIAN_ALBERS = 3577;

    private static final int TILE_CACHE = 16;

    private final FileChannel channel;
    /** The whole file, when it was handed over as bytes rather than a path. */
    private final ByteBuffer memory;
    private final ByteOrder order;
    private final boolean big;
    private final Path path;

    private final int width, height;
    private final int bitsPerSample, samplesPerPixel, sampleFormat, compression, predictor;
    private final boolean tiled;
    private final int tileWidth, tileLength;
    private final long[] chunkOffsets, chunkBytes;
    private final Double nodata;
    private final boolean projected;
    private final boolean pixelIsPoint;
    private final double originX, originY, scaleX, scaleY;

    private final Map<Integer, ByteBuffer> tiles = new LinkedHashMap<>(TILE_CACHE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, ByteBuffer> eldest) {
            return size() > TILE_CACHE;
        }
    };

    public static GeoTiff open(Path path) throws IOException {
        return new GeoTiff(path, FileChannel.open(path, StandardOpenOption.READ), null);
    }

    /**
     * A small raster held in memory, named for the messages.
     */
    public static GeoTiff of(byte[] bytes, String name) throws IOException {
        return new GeoTiff(Path.of(name), null, ByteBuffer.wrap(bytes));
    }

    private GeoTiff(Path path, FileChannel channel, ByteBuffer memory) throws IOException {
        this.path = path;
        this.channel = channel;
        this.memory = memory;
        ByteBuffer head = read(0, 16);
        short bom = head.getShort(0);
        if (bom == 0x4949) {
            order = ByteOrder.LITTLE_ENDIAN;
        } else if (bom == 0x4D4D) {
            order = ByteOrder.BIG_ENDIAN;
        } else {
            throw new IOException(path + " is not a TIFF (no byte order mark)");
        }
        head.order(order);
        int magic = head.getShort(2) & 0xFFFF;
        long firstIfd;
        if (magic == 42) {
            big = false;
            firstIfd = head.getInt(4) & 0xFFFFFFFFL;
        } else if (magic == 43) {
            big = true;
            firstIfd = head.getLong(8);
        } else {
            throw new IOException(path + " is not a TIFF (magic " + magic + ")");
        }

        Map<Integer, Entry> ifd = readIfd(firstIfd);
        width = (int) required(ifd, TAG_WIDTH).longAt(0);
        height = (int) required(ifd, TAG_LENGTH).longAt(0);
        bitsPerSample = (int) optional(ifd, TAG_BITS, 8);
        samplesPerPixel = (int) optional(ifd, TAG_SAMPLES_PER_PIXEL, 1);
        sampleFormat = (int) optional(ifd, TAG_SAMPLE_FORMAT, 1);
        compression = (int) optional(ifd, TAG_COMPRESSION, COMPRESSION_NONE);
        predictor = (int) optional(ifd, TAG_PREDICTOR, 1);
        if (optional(ifd, TAG_PLANAR, 1) != 1 && samplesPerPixel > 1) {
            throw new IOException(path + ": planar multi-band files are not supported");
        }
        if (bitsPerSample != 8 && bitsPerSample != 16 && bitsPerSample != 32 && bitsPerSample != 64) {
            throw new IOException(path + ": " + bitsPerSample + "-bit samples are not supported");
        }
        if (compression != COMPRESSION_NONE && compression != COMPRESSION_LZW && compression != COMPRESSION_DEFLATE
                && compression != COMPRESSION_DEFLATE_OLD && compression != COMPRESSION_PACKBITS) {
            throw new IOException(path + ": compression " + compression + " is not supported (none, LZW, Deflate and PackBits are)");
        }
        tiled = ifd.containsKey(TAG_TILE_OFFSETS);
        if (tiled) {
            tileWidth = (int) required(ifd, TAG_TILE_WIDTH).longAt(0);
            tileLength = (int) required(ifd, TAG_TILE_LENGTH).longAt(0);
            chunkOffsets = required(ifd, TAG_TILE_OFFSETS).longs();
            chunkBytes = required(ifd, TAG_TILE_BYTES).longs();
        } else {
            tileWidth = width;
            long rows = optional(ifd, TAG_ROWS_PER_STRIP, height);
            tileLength = (int) Math.min(rows, height);
            chunkOffsets = required(ifd, TAG_STRIP_OFFSETS).longs();
            chunkBytes = required(ifd, TAG_STRIP_BYTES).longs();
        }

        Entry nodataTag = ifd.get(TAG_GDAL_NODATA);
        Double nd = null;
        if (nodataTag != null) {
            try {
                nd = Double.parseDouble(nodataTag.ascii().trim());
            } catch (NumberFormatException ignored) {
                // GDAL writes "nan" for some floats; a NaN sample is treated as missing regardless.
            }
        }
        nodata = nd;

        // Georeferencing: scale and tie point, or the full transformation matrix.
        Entry transform = ifd.get(TAG_MODEL_TRANSFORMATION);
        Entry scale = ifd.get(TAG_MODEL_PIXEL_SCALE);
        Entry tie = ifd.get(TAG_MODEL_TIEPOINT);
        if (transform != null) {
            double[] m = transform.doubles();
            if (m[1] != 0 || m[4] != 0) {
                throw new IOException(path + ": a rotated raster is not supported");
            }
            scaleX = m[0];
            scaleY = -m[5];
            originX = m[3];
            originY = m[7];
        } else if (scale != null && tie != null) {
            double[] s = scale.doubles();
            double[] t = tie.doubles();
            scaleX = s[0];
            scaleY = s[1];
            // The tie point maps raster (i, j) to model (x, y); shift to the origin of pixel (0, 0).
            originX = t[3] - t[0] * scaleX;
            originY = t[4] + t[1] * scaleY;
        } else {
            throw new IOException(path + ": no georeferencing (neither pixel scale and tie point nor a transformation)");
        }

        Entry keys = ifd.get(TAG_GEO_KEYS);
        int modelType = MODEL_GEOGRAPHIC;
        int rasterType = 1;
        int projectedCs = 0;
        if (keys != null) {
            long[] k = keys.longs();
            int count = (int) k[3];
            for (int i = 0; i < count; i++) {
                int id = (int) k[4 + i * 4];
                int location = (int) k[5 + i * 4];
                int value = (int) k[7 + i * 4];
                if (location != 0) {
                    continue;
                }
                if (id == GEO_MODEL_TYPE) {
                    modelType = value;
                } else if (id == GEO_RASTER_TYPE) {
                    rasterType = value;
                } else if (id == GEO_PROJECTED_CS) {
                    projectedCs = value;
                }
            }
        }
        pixelIsPoint = rasterType == RASTER_PIXEL_IS_POINT;
        if (modelType == MODEL_PROJECTED) {
            if (projectedCs != EPSG_AUSTRALIAN_ALBERS) {
                throw new IOException(path + ": projected raster in EPSG:" + projectedCs
                        + "; only geographic lat/lon and Australian Albers (EPSG:3577) are supported");
            }
            projected = true;
        } else {
            projected = false;
        }
    }

    public Path path() {
        return path;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /**
     * The width of one cell on the ground, roughly, in metres, for the console and the logs.
     */
    public double cellMetres() {
        return projected ? scaleX : scaleX * 111_320;
    }

    /**
     * The value at a point, or NaN where the point is outside the raster or the cell is nodata.
     */
    public synchronized double sample(double lat, double lon) {
        double x, y;
        if (projected) {
            double[] xy = Albers.forward(lat, lon);
            x = xy[0];
            y = xy[1];
        } else {
            x = lon;
            y = lat;
        }
        // PixelIsPoint puts the coordinate at the cell centre; PixelIsArea at its upper-left corner.
        double half = pixelIsPoint ? 0.5 : 0;
        int col = (int) Math.floor((x - originX) / scaleX + half);
        int row = (int) Math.floor((originY - y) / scaleY + half);
        if (col < 0 || row < 0 || col >= width || row >= height) {
            return Double.NaN;
        }
        double v = valueAt(col, row);
        if (Double.isNaN(v) || (nodata != null && v == nodata)) {
            return Double.NaN;
        }
        return v;
    }

    private double valueAt(int col, int row) {
        int tilesAcross = (width + tileWidth - 1) / tileWidth;
        int tileIndex = (row / tileLength) * tilesAcross + col / tileWidth;
        ByteBuffer tile = tiles.get(tileIndex);
        if (tile == null) {
            tile = decode(tileIndex);
            tiles.put(tileIndex, tile);
        }
        int inRow = row % tileLength, inCol = col % tileWidth;
        int bytes = bitsPerSample / 8;
        int index = ((inRow * tileWidth) + inCol) * samplesPerPixel * bytes;
        if (index + bytes > tile.limit()) {
            return Double.NaN;
        }
        return switch (bitsPerSample) {
            case 8 -> sampleFormat == 2 ? tile.get(index) : tile.get(index) & 0xFF;
            case 16 -> sampleFormat == 2 ? tile.getShort(index) : tile.getShort(index) & 0xFFFF;
            case 32 -> sampleFormat == 3 ? tile.getFloat(index)
                    : sampleFormat == 2 ? tile.getInt(index) : tile.getInt(index) & 0xFFFFFFFFL;
            default -> sampleFormat == 3 ? tile.getDouble(index) : (double) tile.getLong(index);
        };
    }

    private ByteBuffer decode(int tileIndex) {
        try {
            if (tileIndex >= chunkOffsets.length) {
                return ByteBuffer.allocate(0);
            }
            int bytesPerSample = bitsPerSample / 8;
            int rowBytes = tileWidth * samplesPerPixel * bytesPerSample;
            int expected = rowBytes * tileLength;
            ByteBuffer raw = read(chunkOffsets[tileIndex], (int) chunkBytes[tileIndex]);
            byte[] data = switch (compression) {
                case COMPRESSION_NONE -> toArray(raw);
                case COMPRESSION_DEFLATE, COMPRESSION_DEFLATE_OLD -> inflate(toArray(raw), expected);
                case COMPRESSION_LZW -> Lzw.decode(toArray(raw), expected);
                case COMPRESSION_PACKBITS -> packBits(toArray(raw), expected);
                default -> throw new IllegalStateException("compression " + compression);
            };
            if (predictor == 2) {
                horizontalPredictor(data, rowBytes, bytesPerSample * samplesPerPixel);
            } else if (predictor == 3) {
                floatingPointPredictor(data, rowBytes, tileWidth * samplesPerPixel, bytesPerSample);
            }
            return ByteBuffer.wrap(data).order(predictor == 3 ? ByteOrder.BIG_ENDIAN : order);
        } catch (IOException | DataFormatException e) {
            throw new IllegalStateException("cannot read tile " + tileIndex + " of " + path + ": " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- codecs

    private static byte[] inflate(byte[] in, int expected) throws DataFormatException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(in);
            byte[] out = new byte[expected];
            int total = 0;
            while (total < expected && !inflater.finished()) {
                int n = inflater.inflate(out, total, expected - total);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                total += n;
            }
            return out;
        } finally {
            inflater.end();
        }
    }

    private static byte[] packBits(byte[] in, int expected) {
        byte[] out = new byte[expected];
        int i = 0, o = 0;
        while (i < in.length && o < expected) {
            int n = in[i++];
            if (n >= 0) {
                int len = Math.min(n + 1, Math.min(expected - o, in.length - i));
                System.arraycopy(in, i, out, o, len);
                i += len;
                o += len;
            } else if (n != -128) {
                byte v = in[i++];
                int len = Math.min(1 - n, expected - o);
                for (int k = 0; k < len; k++) {
                    out[o++] = v;
                }
            }
        }
        return out;
    }

    /**
     * Predictor 2: each sample is stored as the difference from the one to its left.
     */
    private static void horizontalPredictor(byte[] data, int rowBytes, int stride) {
        // Differencing is per sample value, not per byte, so it depends on the sample width. Bytes and
        // shorts are the cases GDAL writes; wider integers are handled the same way per byte lane.
        for (int row = 0; row + rowBytes <= data.length; row += rowBytes) {
            if (stride == 1) {
                for (int i = 1; i < rowBytes; i++) {
                    data[row + i] += data[row + i - 1];
                }
            } else if (stride == 2) {
                for (int i = 2; i + 1 < rowBytes; i += 2) {
                    int prev = ((data[row + i - 2] & 0xFF) << 8) | (data[row + i - 1] & 0xFF);
                    int cur = ((data[row + i] & 0xFF) << 8) | (data[row + i + 1] & 0xFF);
                    int sum = (prev + cur) & 0xFFFF;
                    data[row + i] = (byte) (sum >> 8);
                    data[row + i + 1] = (byte) sum;
                }
            } else {
                for (int i = stride; i < rowBytes; i++) {
                    data[row + i] += data[row + i - stride];
                }
            }
        }
    }

    /**
     * Predictor 3, the floating-point predictor: the bytes of each sample in a row are split into
     * lanes (all the first bytes, then all the second bytes, ...), stored big-endian and differenced
     * across the whole row. Undone here into big-endian samples.
     */
    private static void floatingPointPredictor(byte[] data, int rowBytes, int samplesPerRow, int bytesPerSample) {
        byte[] row = new byte[rowBytes];
        for (int start = 0; start + rowBytes <= data.length; start += rowBytes) {
            for (int i = 1; i < rowBytes; i++) {
                data[start + i] += data[start + i - 1];
            }
            System.arraycopy(data, start, row, 0, rowBytes);
            for (int s = 0; s < samplesPerRow; s++) {
                for (int b = 0; b < bytesPerSample; b++) {
                    data[start + s * bytesPerSample + b] = row[b * samplesPerRow + s];
                }
            }
        }
    }

    // ---------------------------------------------------------------- the IFD

    private Map<Integer, Entry> readIfd(long at) throws IOException {
        Map<Integer, Entry> out = new LinkedHashMap<>();
        long count;
        int entrySize;
        long first;
        if (big) {
            count = read(at, 8).getLong(0);
            entrySize = 20;
            first = at + 8;
        } else {
            count = read(at, 2).getShort(0) & 0xFFFF;
            entrySize = 12;
            first = at + 2;
        }
        ByteBuffer entries = read(first, (int) (count * entrySize));
        for (int i = 0; i < count; i++) {
            int base = i * entrySize;
            int tag = entries.getShort(base) & 0xFFFF;
            int type = entries.getShort(base + 2) & 0xFFFF;
            long n = big ? entries.getLong(base + 4) : entries.getInt(base + 4) & 0xFFFFFFFFL;
            int valueAt = base + (big ? 12 : 8);
            int inline = big ? 8 : 4;
            long size = typeSize(type) * n;
            ByteBuffer value;
            if (size <= inline) {
                value = slice(entries, valueAt, (int) size);
            } else {
                long offset = big ? entries.getLong(valueAt) : entries.getInt(valueAt) & 0xFFFFFFFFL;
                value = read(offset, (int) size);
            }
            out.put(tag, new Entry(type, n, value));
        }
        return out;
    }

    private static int typeSize(int type) {
        return switch (type) {
            case 1, 2, 6, 7 -> 1;
            case 3, 8 -> 2;
            case 4, 9, 11 -> 4;
            case 5, 10, 12, 16, 17, 18 -> 8;
            default -> 1;
        };
    }

    private static Entry required(Map<Integer, Entry> ifd, int tag) throws IOException {
        Entry e = ifd.get(tag);
        if (e == null) {
            throw new IOException("TIFF tag " + tag + " is missing");
        }
        return e;
    }

    private static long optional(Map<Integer, Entry> ifd, int tag, long fallback) {
        Entry e = ifd.get(tag);
        return e == null ? fallback : e.longAt(0);
    }

    private ByteBuffer read(long at, int size) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(Math.max(0, size)).order(order);
        if (memory != null) {
            int from = (int) Math.min(Math.max(0, at), memory.limit());
            int n = Math.min(Math.max(0, size), memory.limit() - from);
            b.put(memory.duplicate().position(from).limit(from + n));
            b.flip();
            return b;
        }
        long position = at;
        while (b.hasRemaining()) {
            int n = channel.read(b, position);
            if (n < 0) {
                break;
            }
            position += n;
        }
        b.flip();
        return b;
    }

    private ByteBuffer slice(ByteBuffer from, int at, int size) {
        ByteBuffer out = ByteBuffer.allocate(size).order(order);
        for (int i = 0; i < size; i++) {
            out.put(from.get(at + i));
        }
        out.flip();
        return out;
    }

    private static byte[] toArray(ByteBuffer b) {
        byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    @Override
    public void close() throws IOException {
        if (channel != null) {
            channel.close();
        }
    }

    /**
     * One IFD entry, with its value bytes already fetched whether they were inline or elsewhere.
     */
    private record Entry(int type, long count, ByteBuffer value) {

        long longAt(int i) {
            return switch (type) {
                case 1, 7 -> value.get(i) & 0xFF;
                case 3 -> value.getShort(i * 2) & 0xFFFF;
                case 4 -> value.getInt(i * 4) & 0xFFFFFFFFL;
                case 16 -> value.getLong(i * 8);
                case 8 -> value.getShort(i * 2);
                case 9 -> value.getInt(i * 4);
                default -> (long) doubleAt(i);
            };
        }

        double doubleAt(int i) {
            return switch (type) {
                case 11 -> value.getFloat(i * 4);
                case 12 -> value.getDouble(i * 8);
                case 5 -> (double) (value.getInt(i * 8) & 0xFFFFFFFFL) / (value.getInt(i * 8 + 4) & 0xFFFFFFFFL);
                default -> longAt(i);
            };
        }

        long[] longs() {
            long[] out = new long[(int) count];
            for (int i = 0; i < out.length; i++) {
                out[i] = longAt(i);
            }
            return out;
        }

        double[] doubles() {
            double[] out = new double[(int) count];
            for (int i = 0; i < out.length; i++) {
                out[i] = doubleAt(i);
            }
            return out;
        }

        String ascii() {
            byte[] b = new byte[(int) count];
            for (int i = 0; i < b.length; i++) {
                b[i] = value.get(i);
            }
            int end = b.length;
            while (end > 0 && b[end - 1] == 0) {
                end--;
            }
            return new String(b, 0, end, StandardCharsets.US_ASCII);
        }
    }

    /**
     * TIFF's LZW: MSB-first codes of 9 to 12 bits, a clear code at 256, end at 257, and the "early
     * change" the TIFF flavour uses, where the code width grows one code earlier than in GIF.
     */
    static final class Lzw {

        private static final int CLEAR = 256, EOI = 257;

        static byte[] decode(byte[] in, int expected) {
            byte[] out = new byte[expected];
            int o = 0;
            byte[][] table = new byte[4096][];
            for (int i = 0; i < 256; i++) {
                table[i] = new byte[]{(byte) i};
            }
            int next = 258;
            int width = 9;
            long bits = 0;
            int have = 0;
            int pos = 0;
            byte[] previous = null;
            while (o < expected) {
                while (have < width) {
                    if (pos >= in.length) {
                        return out;
                    }
                    bits = (bits << 8) | (in[pos++] & 0xFF);
                    have += 8;
                }
                int code = (int) ((bits >> (have - width)) & ((1 << width) - 1));
                have -= width;
                if (code == CLEAR) {
                    next = 258;
                    width = 9;
                    previous = null;
                    continue;
                }
                if (code == EOI) {
                    break;
                }
                byte[] entry;
                if (code < next && table[code] != null) {
                    entry = table[code];
                    if (previous != null && next < 4096) {
                        table[next++] = concat(previous, entry[0]);
                    }
                } else if (previous != null) {
                    entry = concat(previous, previous[0]);
                    if (next < 4096) {
                        table[next++] = entry;
                    }
                } else {
                    break;
                }
                int len = Math.min(entry.length, expected - o);
                System.arraycopy(entry, 0, out, o, len);
                o += len;
                previous = entry;
                if (next + 1 >= (1 << width) && width < 12) {
                    width++;
                }
            }
            return out;
        }

        private static byte[] concat(byte[] a, byte b) {
            byte[] out = new byte[a.length + 1];
            System.arraycopy(a, 0, out, 0, a.length);
            out[a.length] = b;
            return out;
        }
    }
}
