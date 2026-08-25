package cn.jianda.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

public final class OriginalFileHttp {
    private OriginalFileHttp() {}

    public static ResponseEntity<byte[]> response(
            DocumentService.OriginalFile file, String rangeHeader, boolean download) throws IOException {
        byte[] all = Files.readAllBytes(file.path());
        HttpHeaders headers = baseHeaders(file, download);
        if (rangeHeader == null || rangeHeader.isBlank()) {
            headers.setContentLength(all.length);
            return new ResponseEntity<>(all, headers, HttpStatus.OK);
        }
        long[] range = parseRange(rangeHeader, all.length);
        if (range == null) {
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes */" + all.length);
            return new ResponseEntity<>(new byte[0], headers, HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        }
        int start = Math.toIntExact(range[0]);
        int end = Math.toIntExact(range[1]);
        byte[] part = Arrays.copyOfRange(all, start, end + 1);
        headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + all.length);
        headers.setContentLength(part.length);
        return new ResponseEntity<>(part, headers, HttpStatus.PARTIAL_CONTENT);
    }

    private static HttpHeaders baseHeaders(DocumentService.OriginalFile file, boolean download) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(effectiveMimeType(file)));
        ContentDisposition.Builder disposition = download
                ? ContentDisposition.attachment() : ContentDisposition.inline();
        headers.setContentDisposition(disposition
                .filename(file.filename(), StandardCharsets.UTF_8).build());
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setETag("\"" + file.sha256() + "\"");
        headers.set("X-Content-SHA256", file.sha256());
        return headers;
    }

    private static String effectiveMimeType(DocumentService.OriginalFile file) {
        String mimeType = file.mimeType();
        if (mimeType != null && !mimeType.isBlank()
                && !mimeType.toLowerCase(Locale.ROOT)
                        .startsWith(MediaType.APPLICATION_OCTET_STREAM_VALUE)) {
            return mimeType;
        }
        String filename = file.filename().toLowerCase(Locale.ROOT);
        if (filename.endsWith(".pdf")) return MediaType.APPLICATION_PDF_VALUE;
        if (filename.endsWith(".png")) return MediaType.IMAGE_PNG_VALUE;
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG_VALUE;
        }
        if (filename.endsWith(".webp")) return "image/webp";
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    private static long[] parseRange(String value, int length) {
        if (!value.startsWith("bytes=") || value.contains(",") || length == 0) return null;
        String[] parts = value.substring(6).split("-", -1);
        if (parts.length != 2) return null;
        try {
            long start;
            long end;
            if (parts[0].isBlank()) {
                long suffix = Long.parseLong(parts[1]);
                if (suffix <= 0) return null;
                start = Math.max(0, length - suffix);
                end = length - 1L;
            } else {
                start = Long.parseLong(parts[0]);
                end = parts[1].isBlank() ? length - 1L : Long.parseLong(parts[1]);
            }
            if (start < 0 || start >= length || end < start) return null;
            return new long[] {start, Math.min(end, length - 1L)};
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
