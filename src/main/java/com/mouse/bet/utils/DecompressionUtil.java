package com.mouse.bet.utils;

import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Brotli support
import com.aayushatharva.brotli4j.Brotli4jLoader;
import com.aayushatharva.brotli4j.decoder.BrotliInputStream;

// Zstandard support
import com.github.luben.zstd.ZstdInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

public class DecompressionUtil {

    private static final Logger logger = LoggerFactory.getLogger(DecompressionUtil.class);
    private static final int BUFFER_SIZE = 8192;
    private static boolean brotliLoaded = false;

    static {
        // Initialize Brotli native library
        try {
            Brotli4jLoader.ensureAvailability();
            brotliLoaded = true;
            logger.info("Brotli native library loaded successfully");
        } catch (Throwable e) {
            logger.warn("Brotli native library not available: {}", e.getMessage());
        }
    }

    /**
     * Decompresses response from OkHttp, supporting: gzip, deflate, brotli (br), and zstd.
     * Automatically detects compression format from Content-Encoding header or magic bytes.
     *
     * @param response OkHttp Response object
     * @return Decompressed string
     * @throws IOException if decompression fails
     */
    public static String decompressResponse(Response response) throws IOException {
        if (response.body() == null) {
            throw new IOException("Response body is null");
        }

        // Check Content-Encoding header
        String contentEncoding = response.header("Content-Encoding");
        logger.debug("Content-Encoding header: {}", contentEncoding);

        byte[] data = response.body().bytes();

        // Try to decompress based on Content-Encoding header
        if (contentEncoding != null && !contentEncoding.isEmpty()) {
            contentEncoding = contentEncoding.toLowerCase().trim();

            try {
                if (contentEncoding.contains("br") || contentEncoding.contains("brotli")) {
                    logger.debug("Attempting Brotli decompression");
                    return decompressWithBrotli(data);
                } else if (contentEncoding.contains("zstd")) {
                    logger.debug("Attempting Zstandard decompression");
                    return decompressWithZstd(data);
                } else if (contentEncoding.contains("gzip")) {
                    logger.debug("Attempting GZIP decompression");
                    return decompressWithGzip(data);
                } else if (contentEncoding.contains("deflate")) {
                    logger.debug("Attempting DEFLATE decompression");
                    return inflateData(data, false);
                }
            } catch (Exception e) {
                logger.warn("Decompression failed for Content-Encoding '{}': {}", contentEncoding, e.getMessage());
            }
        }

        // Fallback to auto-detection
        return decompressBytes(data);
    }

    /**
     * Main decompression method - tries all compression formats with smart detection.
     *
     * @param compressedData Compressed byte array
     * @return Decompressed string
     * @throws IOException if all decompression methods fail
     */
    public static String decompressBytes(byte[] compressedData) throws IOException {
        if (compressedData == null || compressedData.length == 0) {
            throw new IOException("Compressed data is null or empty");
        }

        logger.debug("Attempting to decompress {} bytes", compressedData.length);
        logger.debug("First 16 bytes (hex): {}", bytesToHex(compressedData, 16));

        // Check magic bytes and try appropriate decompressor
        if (compressedData.length >= 2) {
            int b0 = compressedData[0] & 0xFF;
            int b1 = compressedData[1] & 0xFF;

            // GZIP: 1F 8B
            if (b0 == 0x1F && b1 == 0x8B) {
                try {
                    String result = decompressWithGzip(compressedData);
                    logger.info("✓ Decompressed with GZIP (magic bytes)");
                    return result;
                } catch (Exception e) {
                    logger.debug("GZIP failed: {}", e.getMessage());
                }
            }

            // zlib/DEFLATE: 78 01, 78 5E, 78 9C, 78 DA
            if (b0 == 0x78 && (b1 == 0x01 || b1 == 0x5E || b1 == 0x9C || b1 == 0xDA)) {
                try {
                    String result = inflateData(compressedData, false);
                    logger.info("✓ Decompressed with DEFLATE/zlib (magic bytes)");
                    return result;
                } catch (Exception e) {
                    logger.debug("zlib failed: {}", e.getMessage());
                }
            }

            // Zstandard: 28 B5 2F FD
            if (compressedData.length >= 4 &&
                    b0 == 0x28 && b1 == 0xB5 &&
                    (compressedData[2] & 0xFF) == 0x2F &&
                    (compressedData[3] & 0xFF) == 0xFD) {
                try {
                    String result = decompressWithZstd(compressedData);
                    logger.info("✓ Decompressed with Zstandard (magic bytes)");
                    return result;
                } catch (Exception e) {
                    logger.debug("Zstandard failed: {}", e.getMessage());
                }
            }
        }

        // Try all methods in order of likelihood

        // 1. Brotli (common for modern betting sites)
        if (brotliLoaded) {
            try {
                String result = decompressWithBrotli(compressedData);
                logger.info("✓ Decompressed with Brotli");
                return result;
            } catch (Exception e) {
                logger.debug("Brotli failed: {}", e.getMessage());
            }
        }

        // 2. Zstandard
        try {
            String result = decompressWithZstd(compressedData);
            logger.info("✓ Decompressed with Zstandard");
            return result;
        } catch (Exception e) {
            logger.debug("Zstandard failed: {}", e.getMessage());
        }

        // 3. GZIP
        try {
            String result = decompressWithGzip(compressedData);
            logger.info("✓ Decompressed with GZIP");
            return result;
        } catch (Exception e) {
            logger.debug("GZIP failed: {}", e.getMessage());
        }

        // 4. DEFLATE with zlib wrapper
        try {
            String result = inflateData(compressedData, false);
            logger.info("✓ Decompressed with DEFLATE (zlib wrapper)");
            return result;
        } catch (Exception e) {
            logger.debug("DEFLATE with wrapper failed: {}", e.getMessage());
        }

        // 5. Raw DEFLATE (no wrapper)
        try {
            String result = inflateData(compressedData, true);
            logger.info("✓ Decompressed with raw DEFLATE");
            return result;
        } catch (Exception e) {
            logger.debug("Raw DEFLATE failed: {}", e.getMessage());
        }

        // 6. InflaterInputStream alternative
        try {
            String result = decompressWithInflaterStream(compressedData, true);
            logger.info("✓ Decompressed with InflaterInputStream");
            return result;
        } catch (Exception e) {
            logger.debug("InflaterInputStream failed: {}", e.getMessage());
        }

        // 7. ZIP file
        try {
            String result = decompressWithZip(compressedData);
            logger.info("✓ Decompressed as ZIP");
            return result;
        } catch (Exception e) {
            logger.debug("ZIP failed: {}", e.getMessage());
        }

        // 8. Check if already plain text
        String asString = tryAsPlainText(compressedData);
        if (asString != null) {
            logger.warn("Data appears to be already uncompressed");
            return asString;
        }

        // Failed all methods
        logger.error("All decompression methods failed. Data analysis:");
        logger.error(analyzeCompressionFormat(compressedData));

        throw new IOException("Failed to decompress data with all available methods (gzip, deflate, brotli, zstd). " +
                "Data might be encrypted or use custom compression. Length: " + compressedData.length + " bytes");
    }

    /**
     * Decompresses Brotli compressed data.
     */
    private static String decompressWithBrotli(byte[] compressedData) throws IOException {
        if (!brotliLoaded) {
            throw new IOException("Brotli native library not loaded");
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressedData);
             BrotliInputStream bris = new BrotliInputStream(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = bris.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }

            byte[] result = baos.toByteArray();
            if (result.length == 0) {
                throw new IOException("Brotli decompression resulted in empty output");
            }

            return new String(result, StandardCharsets.UTF_8);
        }
    }

    /**
     * Decompresses Zstandard compressed data.
     */
    private static String decompressWithZstd(byte[] compressedData) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressedData);
             ZstdInputStream zis = new ZstdInputStream(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = zis.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }

            byte[] result = baos.toByteArray();
            if (result.length == 0) {
                throw new IOException("Zstandard decompression resulted in empty output");
            }

            return new String(result, StandardCharsets.UTF_8);
        }
    }

    /**
     * Decompresses GZIP data.
     */
    private static String decompressWithGzip(byte[] compressedData) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressedData);
             GZIPInputStream gis = new GZIPInputStream(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = gis.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }

            byte[] result = baos.toByteArray();
            if (result.length == 0) {
                throw new IOException("GZIP decompression resulted in empty output");
            }

            return new String(result, StandardCharsets.UTF_8);
        }
    }

    /**
     * Inflates DEFLATE compressed data.
     */
    private static String inflateData(byte[] compressedData, boolean nowrap) throws IOException {
        Inflater inflater = new Inflater(nowrap);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(compressedData.length * 2, BUFFER_SIZE));

        try {
            inflater.setInput(compressedData);
            byte[] buffer = new byte[BUFFER_SIZE];

            while (!inflater.finished()) {
                int count;
                try {
                    count = inflater.inflate(buffer);
                } catch (DataFormatException e) {
                    throw new IOException("Invalid DEFLATE data: " + e.getMessage(), e);
                }

                if (count == 0) {
                    break;
                }
                baos.write(buffer, 0, count);
            }

            byte[] result = baos.toByteArray();
            if (result.length == 0) {
                throw new IOException("DEFLATE decompression resulted in empty output");
            }

            return new String(result, StandardCharsets.UTF_8);
        } finally {
            inflater.end();
            baos.close();
        }
    }

    /**
     * Alternative DEFLATE method using InflaterInputStream.
     */
    private static String decompressWithInflaterStream(byte[] compressedData, boolean nowrap) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressedData);
             InflaterInputStream iis = new InflaterInputStream(bais, new Inflater(nowrap));
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = iis.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }

            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Decompresses ZIP file data.
     */
    private static String decompressWithZip(byte[] compressedData) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressedData);
             ZipInputStream zis = new ZipInputStream(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            ZipEntry entry = zis.getNextEntry();
            if (entry == null) {
                throw new IOException("No ZIP entries found");
            }

            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = zis.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }

            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Tries to interpret data as plain text in various encodings.
     */
    private static String tryAsPlainText(byte[] data) {
        Charset[] charsets = {
                StandardCharsets.UTF_8,
                StandardCharsets.ISO_8859_1,
                Charset.forName("windows-1252")
        };

        for (Charset charset : charsets) {
            try {
                String text = new String(data, charset);
                if (isPrintableText(text)) {
                    logger.debug("Data appears to be plain text in {}", charset.name());
                    return text;
                }
            } catch (Exception e) {
                // Try next charset
            }
        }
        return null;
    }

    /**
     * Checks if text is mostly printable.
     */
    private static boolean isPrintableText(String text) {
        if (text == null || text.isEmpty()) return false;

        int printable = 0;
        int total = Math.min(text.length(), 1000);

        for (int i = 0; i < total; i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || Character.isWhitespace(c) ||
                    (c >= 32 && c <= 126) || c == '\n' || c == '\r' || c == '\t') {
                printable++;
            }
        }

        return (printable * 100.0 / total) > 80;
    }

    /**
     * Converts bytes to hex string.
     */
    public static String bytesToHex(byte[] data, int length) {
        if (data == null) return "null";

        int len = Math.min(data.length, length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02X ", data[i] & 0xFF));
        }
        return sb.toString().trim();
    }

    /**
     * Analyzes compression format based on magic bytes.
     */
    public static String analyzeCompressionFormat(byte[] data) {
        if (data == null || data.length < 2) {
            return "Data too short to analyze";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Data length: ").append(data.length).append(" bytes\n");
        sb.append("First 20 bytes (hex): ").append(bytesToHex(data, 20)).append("\n");

        int b0 = data[0] & 0xFF;
        int b1 = data.length > 1 ? data[1] & 0xFF : 0;

        if (b0 == 0x1F && b1 == 0x8B) {
            sb.append("Format: GZIP\n");
        } else if (b0 == 0x78 && (b1 == 0x9C || b1 == 0x01 || b1 == 0xDA || b1 == 0x5E)) {
            sb.append("Format: zlib/DEFLATE\n");
        } else if (data.length >= 4 && b0 == 0x28 && b1 == 0xB5 &&
                (data[2] & 0xFF) == 0x2F && (data[3] & 0xFF) == 0xFD) {
            sb.append("Format: Zstandard\n");
        } else if (b0 == 0x50 && b1 == 0x4B) {
            sb.append("Format: ZIP\n");
        } else {
            sb.append("Format: Unknown (possibly Brotli, raw DEFLATE, or encrypted)\n");
        }

        return sb.toString();
    }
}