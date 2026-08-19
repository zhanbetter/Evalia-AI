# MinIO Multipart Upload + Redis Bitmap: Resumable Dataset Upload

## Context: Existing Codebase

The project already has:
- **MinIO 8.5.7** (`io.minio:minio`) with `MinioServiceImpl` doing simple `putObject` (no multipart)
- **Apache POI 5.2.5** with `XSSFWorkbook` (DOM-based, loads entire file into memory)
- **Spring Boot 2.7.18** / Java 17
- CSV parsing via custom `parseCsvLines()` that loads entire file into memory via `readAllBytes()`
- No Redis integration yet

Current problems at 100K rows:
- `DatasetServiceImpl.upload()` loads all items into a `List<EvalDatasetItem>` before DB insert
- `XSSFWorkbook` for XLSX loads entire DOM tree
- No chunking/resume support -- a network hiccup at 90% means full restart
- Single `putObject` call fails on large files without multipart

---

## 1. MinIO Multipart Upload API (Java SDK 8.5.7)

### Maven dependency (already present)

```xml
<dependency>
    <groupId>io.minio</groupId>
    <artifactId>minio</artifactId>
    <version>8.5.7</version>
</dependency>
```

### Chunk size recommendation

| File type | Recommended chunk | Why |
|-----------|------------------|-----|
| CSV (100K rows) | 5-10 MB | Rows are small text; 5 MB captures ~2K-5K rows per chunk |
| XLSX (100K rows) | 5-10 MB | Compressed XML; similar density |
| JSON | 5-10 MB | Text-heavy |

MinIO minimum part size is 5 MB (except the last part). Use **5 MB** as default for dataset files.

### Complete Multipart Upload Java Code

```java
package com.eval.service;

import io.minio.*;
import io.minio.messages.Part;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MultipartUploadService {

    private final MinioClient minioClient;

    private static final int PART_SIZE = 5 * 1024 * 1024; // 5 MB

    /**
     * Step 1: Initiate a multipart upload.
     * Returns the uploadId used for subsequent part uploads.
     */
    public String initiateUpload(String bucket, String objectName) {
        try {
            CreateMultipartUploadResponse response = minioClient.createMultipartUpload(
                    CreateMultipartUploadArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .build()
            );
            String uploadId = response.result().uploadId();
            log.info("Multipart upload initiated: bucket={}, object={}, uploadId={}",
                    bucket, objectName, uploadId);
            return uploadId;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initiate multipart upload", e);
        }
    }

    /**
     * Step 2: Upload a single part.
     * Returns the ETag needed for completion.
     */
    public Part uploadPart(String bucket, String objectName, String uploadId,
                           int partNumber, InputStream data, long partSize) {
        try {
            UploadPartResponse response = minioClient.uploadPart(
                    UploadPartArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .uploadId(uploadId)
                            .partNumber(partNumber)
                            .stream(data, partSize, -1)
                            .build()
            );
            Part part = Part.builder()
                    .partNumber(partNumber)
                    .etag(response.etag())
                    .build();
            log.debug("Part {} uploaded: etag={}, size={}", partNumber, response.etag(), partSize);
            return part;
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload part " + partNumber, e);
        }
    }

    /**
     * Step 3: Complete the multipart upload after all parts are uploaded.
     */
    public void completeUpload(String bucket, String objectName, String uploadId, List<Part> parts) {
        try {
            minioClient.completeMultipartUpload(
                    CompleteMultipartUploadArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .uploadId(uploadId)
                            .parts(parts)
                            .build()
            );
            log.info("Multipart upload completed: bucket={}, object={}, parts={}",
                    bucket, objectName, parts.size());
        } catch (Exception e) {
            throw new RuntimeException("Failed to complete multipart upload", e);
        }
    }

    /**
     * Step 4 (on failure/cancel): Abort the multipart upload and discard all uploaded parts.
     */
    public void abortUpload(String bucket, String objectName, String uploadId) {
        try {
            minioClient.abortMultipartUpload(
                    AbortMultipartUploadArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .uploadId(uploadId)
                            .build()
            );
            log.info("Multipart upload aborted: bucket={}, object={}, uploadId={}",
                    bucket, objectName, uploadId);
        } catch (Exception e) {
            log.error("Failed to abort multipart upload", e);
        }
    }

    /**
     * Generate a presigned URL for a specific part upload.
     * The frontend can PUT directly to this URL, bypassing the backend for the heavy data transfer.
     */
    public String presignPartUpload(String bucket, String objectName, String uploadId,
                                    int partNumber, int expirySeconds) {
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(bucket)
                            .object(objectName)
                            .expiry(expirySeconds)
                            .extraQueryParams(Map.of(
                                    "uploadId", uploadId,
                                    "partNumber", String.valueOf(partNumber)
                            ))
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate presigned URL", e);
        }
    }
}
```

### Key API notes for MinIO Java SDK 8.5.7

- `createMultipartUpload()` returns `CreateMultipartUploadResponse` whose `.result().uploadId()` is the string upload ID
- `uploadPart()` requires exact `partSize` in the `stream()` call -- use the actual bytes read, not a fixed value for the last part
- `completeMultipartUpload()` requires parts in ascending `partNumber` order
- Parts are 1-indexed (part 1, part 2, ... not 0-indexed)
- MinIO enforces minimum 5 MB per part except the last
- Max 10,000 parts per upload (5 MB x 10,000 = 50 GB max, more than enough)

---

## 2. Redis Bitmap for Tracking Upload Parts

### Why Redis Bitmap instead of Set/Hash

| Approach | 100K parts memory | Check completeness | Atomic |
|----------|-------------------|-------------------|--------|
| Redis SET | ~3-5 MB (100K strings) | `SCARD` + compare | Yes |
| Redis HASH | ~2-4 MB | `HLEN` + compare | Yes |
| **Redis Bitmap** | **~12.5 KB** (100K bits) | `BITCOUNT` vs total | Yes |

Redis Bitmap is the clear winner: 12.5 KB for 100K parts vs megabytes for SET/HASH.

### Redis commands

```bash
# Init: no setup needed, just start setting bits

# Mark part 3 as uploaded (1-indexed, use 0-based offset: part N = bit N-1)
SETBIT upload:{sessionId}:parts 2 1

# Check if part 3 is uploaded
GETBIT upload:{sessionId}:parts 2
# Returns: 1 (yes) or 0 (no)

# How many parts uploaded so far?
BITCOUNT upload:{sessionId}:parts
# Returns: number of set bits

# Set TTL on the key (24 hours)
EXPIRE upload:{sessionId}:parts 86400

# Get all metadata about the upload session
HSET upload:{sessionId}:meta uploadId "abc123" bucket "llm-eval" \
  objectName "datasets/xxx.csv" totalParts 100000 \
  originalFilename "data.csv" createdAt "2026-08-19T10:00:00"
```

### Java code using Spring Data Redis

```java
package com.eval.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UploadTrackingService {

    private final StringRedisTemplate redisTemplate;

    private static final String PARTS_KEY_PREFIX = "upload:";
    private static final String META_KEY_PREFIX = "upload:";
    private static final Duration UPLOAD_TTL = Duration.ofHours(24);

    /**
     * Initialize tracking for a new upload session.
     * Stores metadata and creates the bitmap key.
     */
    public void initSession(String sessionId, String uploadId, String bucket,
                            String objectName, int totalParts, String originalFilename) {
        String metaKey = META_KEY_PREFIX + sessionId + ":meta";
        Map<String, String> meta = new HashMap<>();
        meta.put("uploadId", uploadId);
        meta.put("bucket", bucket);
        meta.put("objectName", objectName);
        meta.put("totalParts", String.valueOf(totalParts));
        meta.put("originalFilename", originalFilename);
        meta.put("createdAt", java.time.LocalDateTime.now().toString());

        redisTemplate.opsForHash().putAll(metaKey, meta);
        redisTemplate.expire(metaKey, UPLOAD_TTL);

        // Bitmap key is created lazily on first SETBIT, but set TTL
        String partsKey = PARTS_KEY_PREFIX + sessionId + ":parts";
        redisTemplate.expire(partsKey, UPLOAD_TTL);
    }

    /**
     * Mark a part as uploaded. partNumber is 1-indexed.
     */
    public void markPartUploaded(String sessionId, int partNumber) {
        String key = PARTS_KEY_PREFIX + sessionId + ":parts";
        // Bitmap offset is 0-based: part 1 = offset 0, part N = offset N-1
        redisTemplate.opsForValue().setBit(key, partNumber - 1, true);
        redisTemplate.expire(key, UPLOAD_TTL);
    }

    /**
     * Check if a specific part has been uploaded.
     */
    public boolean isPartUploaded(String sessionId, int partNumber) {
        String key = PARTS_KEY_PREFIX + sessionId + ":parts";
        Boolean bit = redisTemplate.opsForValue().getBit(key, partNumber - 1);
        return Boolean.TRUE.equals(bit);
    }

    /**
     * Count how many parts have been uploaded.
     */
    public long getUploadedCount(String sessionId) {
        String key = PARTS_KEY_PREFIX + sessionId + ":parts";
        Long count = redisTemplate.opsForValue().bitCount(key);
        return count != null ? count : 0;
    }

    /**
     * Check if ALL parts have been uploaded.
     */
    public boolean isComplete(String sessionId) {
        Map<Object, Object> meta = redisTemplate.opsForHash()
                .entries(META_KEY_PREFIX + sessionId + ":meta");
        int totalParts = Integer.parseInt((String) meta.getOrDefault("totalParts", "0"));
        return getUploadedCount(sessionId) >= totalParts;
    }

    /**
     * Get upload progress percentage.
     */
    public double getProgress(String sessionId) {
        Map<Object, Object> meta = redisTemplate.opsForHash()
                .entries(META_KEY_PREFIX + sessionId + ":meta");
        int totalParts = Integer.parseInt((String) meta.getOrDefault("totalParts", "0"));
        if (totalParts == 0) return 0;
        return (getUploadedCount(sessionId) * 100.0) / totalParts;
    }

    /**
     * Get all metadata for an upload session.
     */
    public Map<Object, Object> getSessionMeta(String sessionId) {
        return redisTemplate.opsForHash()
                .entries(META_KEY_PREFIX + sessionId + ":meta");
    }

    /**
     * Get the list of part numbers that have NOT been uploaded yet.
     * Used for resume: client skips these and continues from the first missing part.
     */
    public int[] getMissingParts(String sessionId) {
        Map<Object, Object> meta = redisTemplate.opsForHash()
                .entries(META_KEY_PREFIX + sessionId + ":meta");
        int totalParts = Integer.parseInt((String) meta.getOrDefault("totalParts", "0"));
        String key = PARTS_KEY_PREFIX + sessionId + ":parts";

        java.util.List<Integer> missing = new java.util.ArrayList<>();
        for (int i = 0; i < totalParts; i++) {
            Boolean bit = redisTemplate.opsForValue().getBit(key, i);
            if (!Boolean.TRUE.equals(bit)) {
                missing.add(i + 1); // 1-indexed
            }
        }
        return missing.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Clean up the upload session.
     */
    public void cleanup(String sessionId) {
        redisTemplate.delete(PARTS_KEY_PREFIX + sessionId + ":parts");
        redisTemplate.delete(META_KEY_PREFIX + sessionId + ":meta");
    }
}
```

### Performance note on getMissingParts()

For 100K parts, `getMissingParts()` makes 100K `GETBIT` calls -- too slow for resume. Better approach: use Redis `BITFIELD` or do it server-side:

```java
/**
 * Efficient alternative: get all missing parts in one pipeline call.
 * Uses Redis pipeline to batch GETBIT calls into a single round-trip.
 */
public int[] getMissingPartsEfficient(String sessionId) {
    Map<Object, Object> meta = redisTemplate.opsForHash()
            .entries(META_KEY_PREFIX + sessionId + ":meta");
    int totalParts = Integer.parseInt((String) meta.getOrDefault("totalParts", "0"));
    String key = PARTS_KEY_PREFIX + sessionId + ":parts";

    // Pipeline all GETBIT calls into a single Redis round-trip
    List<Object> results = redisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
        for (int i = 0; i < totalParts; i++) {
            connection.getBit(key.getBytes(), i);
        }
        return null;
    });

    java.util.List<Integer> missing = new java.util.ArrayList<>();
    for (int i = 0; i < results.size(); i++) {
        if (Boolean.FALSE.equals(results.get(i)) || results.get(i) == null) {
            missing.add(i + 1);
        }
    }
    return missing.stream().mapToInt(Integer::intValue).toArray();
}
```

---

## 3. Streaming CSV/Excel Parsing (100K Rows, Low Memory)

### Current problem

`DatasetServiceImpl.parseFile()` uses:
- `XSSFWorkbook(is)` -- DOM model, ~100-500 MB for 100K rows
- `is.readAllBytes()` for CSV/JSON -- loads entire file into heap

### 3a. Streaming CSV with OpenCSV

Add dependency to `llm-eval-service/pom.xml`:

```xml
<dependency>
    <groupId>com.opencsv</groupId>
    <artifactId>opencsv</artifactId>
    <version>5.9</version>
</dependency>
```

Streaming CSV parser:

```java
package com.eval.service.parser;

import com.opencsv.CSVReaderBuilder;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Streaming CSV parser that processes rows one at a time.
 * Memory usage: O(1) per row, not O(N) for the entire file.
 */
public class StreamingCsvParser {

    /**
     * Parse CSV from an InputStream, calling the rowCallback for each data row.
     * The header row is extracted first and passed to the callback.
     *
     * @param inputStream  the CSV file stream
     * @param rowCallback  receives (headerNames, rowIndex, rowValues) for each row
     * @return total number of data rows processed
     */
    public static int parse(InputStream inputStream, CsvRowCallback rowCallback) {
        int rowIndex = 0;
        try (CSVReader reader = new CSVReaderBuilder(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))
                .build()) {

            String[] header = reader.readNext();
            if (header == null) {
                throw new IllegalArgumentException("CSV file is empty");
            }

            String[] line;
            while ((line = reader.readNext()) != null) {
                rowCallback.onRow(header, rowIndex, line);
                rowIndex++;
            }
        } catch (Exception e) {
            throw new RuntimeException("CSV parsing failed at row " + rowIndex, e);
        }
        return rowIndex;
    }

    @FunctionalInterface
    public interface CsvRowCallback {
        /**
         * @param header   column names from row 0
         * @param rowIndex 0-based index of the current data row
         * @param values   cell values for this row
         */
        void onRow(String[] header, int rowIndex, String[] values);
    }
}
```

### 3b. Streaming XLSX with Apache POI SAX (event-based)

No new dependency needed -- POI 5.2.5 is already in the project.

```java
package com.eval.service.parser;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.BulkLoader;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.SheetContentsHandler;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import javax.xml.parsers.SAXParserFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Streaming XLSX parser using Apache POI's SAX-based event model.
 * Only holds one row in memory at a time.
 * Memory usage: ~2-5 MB regardless of file size.
 */
public class StreamingXlsxParser {

    /**
     * Parse XLSX from an InputStream using SAX event model.
     *
     * @param inputStream the XLSX file stream
     * @param rowCallback receives (rowIndex, cellValues) for each data row
     * @return total number of data rows processed
     */
    public static int parse(InputStream inputStream, XlsxRowCallback rowCallback) {
        try (OPCPackage pkg = OPCPackage.open(inputStream)) {
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
            XSSFReader reader = new XSSFReader(pkg);
            StylesTable styles = reader.getStylesTable();

            XSSFReader.SheetIterator sheets =
                    (XSSFReader.SheetIterator) reader.getSheetsData();

            int totalRows = 0;

            if (sheets.hasNext()) {
                InputStream sheetStream = sheets.next();

                // State held per-row (inner class captures these)
                final int[] rowIndex = {0};
                final List<String> currentRow = new ArrayList<>();

                SheetContentsHandler handler = new SheetContentsHandler() {
                    @Override
                    public void startRow(int rowNum) {
                        currentRow.clear();
                        rowIndex[0] = rowNum;
                    }

                    @Override
                    public void endRow(int rowNum) {
                        // Skip header row (row 0) if needed; for datasets row 0 is header
                        String[] values = currentRow.toArray(new String[0]);
                        rowCallback.onRow(rowNum, values);
                        totalRows++;
                    }

                    @Override
                    public void cell(String cellReference, String formattedValue,
                                     org.apache.poi.xssf.usermodel.XSSFComment comment) {
                        currentRow.add(formattedValue != null ? formattedValue : "");
                    }
                };

                SAXParserFactory factory = SAXParserFactory.newInstance();
                factory.setNamespaceAware(true);
                XMLReader xmlReader = factory.newSAXParser().getXMLReader();

                XSSFSheetXMLHandler sheetHandler = new XSSFSheetXMLHandler(
                        styles, strings, handler, false);

                xmlReader.setContentHandler(sheetHandler);
                xmlReader.parse(new InputSource(sheetStream));
            }

            return totalRows;
        } catch (Exception e) {
            throw new RuntimeException("XLSX streaming parse failed", e);
        }
    }

    @FunctionalInterface
    public interface XlsxRowCallback {
        /**
         * @param rowNum  0-based row number (0 = header row)
         * @param values  cell values for this row
         */
        void onRow(int rowNum, String[] values);
    }
}
```

### Memory comparison

| Approach | 100K rows CSV | 100K rows XLSX | Heap needed |
|----------|--------------|----------------|-------------|
| Current (DOM / readAllBytes) | ~200 MB | ~500 MB | High |
| Streaming (OpenCSV + POI SAX) | ~2 MB | ~5 MB | Low |

---

## 4. Complete Upload Flow

### 4a. Frontend: JavaScript chunked upload with resume

```javascript
// DatasetUpload.vue - key logic (Vue3 + Composition API)

const CHUNK_SIZE = 5 * 1024 * 1024; // 5 MB

// Step 1: Init upload session on backend
async function initUpload(file, metadata) {
  const totalChunks = Math.ceil(file.size / CHUNK_SIZE);
  const response = await fetch('/api/datasets/upload/init', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      filename: file.name,
      fileSize: file.size,
      totalChunks: totalChunks,
      ...metadata
    })
  });
  return await response.json(); // { sessionId, presignedUrls: [...] }
}

// Step 2: Upload chunks, skipping already-completed ones
async function uploadFile(file, sessionId, totalChunks, onProgress) {
  // Check which chunks are already done (resume)
  const statusResp = await fetch(`/api/datasets/upload/${sessionId}/status`);
  const { completedChunks } = await statusResp.json(); // e.g. [0,1,2,3,4]

  const completedSet = new Set(completedChunks);

  for (let i = 0; i < totalChunks; i++) {
    if (completedSet.has(i)) {
      continue; // Skip already uploaded
    }

    const start = i * CHUNK_SIZE;
    const end = Math.min(start + CHUNK_SIZE, file.size);
    const chunk = file.slice(start, end);

    // Upload via presigned URL (direct to MinIO, no backend proxy)
    // Or via backend proxy: POST /api/datasets/upload/{sessionId}/chunk
    const formData = new FormData();
    formData.append('chunk', chunk);
    formData.append('chunkIndex', i);

    await fetch(`/api/datasets/upload/${sessionId}/chunk`, {
      method: 'POST',
      body: formData
    });

    onProgress(Math.round(((i + 1) / totalChunks) * 100));
  }

  // Step 3: Signal completion
  const result = await fetch(`/api/datasets/upload/${sessionId}/complete`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ schemaFields, columnMapping })
  });
  return await result.json();
}
```

### 4b. Backend: Complete Spring Boot upload flow

```java
package com.eval.web.controller;

import com.eval.common.result.Result;
import com.eval.service.DatasetUploadService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/datasets/upload")
@RequiredArgsConstructor
public class DatasetUploadController {

    private final DatasetUploadService uploadService;

    @Data
    public static class InitUploadRequest {
        private String filename;
        private long fileSize;
        private int totalChunks;
        private String name;
        private String description;
        private Integer hasReference;
        private Integer hasModelResponse;
    }

    /**
     * Step 1: Initialize multipart upload session.
     * Creates MinIO multipart upload, records metadata in Redis.
     */
    @PostMapping("/init")
    public Result<Map<String, Object>> initUpload(@RequestBody InitUploadRequest req) {
        return Result.success(uploadService.initUploadSession(req));
    }

    /**
     * Step 2a: Upload a single chunk via backend proxy.
     * Alternative to presigned URLs -- useful when MinIO is not publicly accessible.
     */
    @PostMapping("/{sessionId}/chunk")
    public Result<Void> uploadChunk(
            @PathVariable String sessionId,
            @RequestParam("chunk") MultipartFile chunk,
            @RequestParam("chunkIndex") int chunkIndex) {
        uploadService.uploadChunk(sessionId, chunkIndex, chunk);
        return Result.success();
    }

    /**
     * Step 2b: Check upload status for resume.
     * Returns which chunks are already completed.
     */
    @GetMapping("/{sessionId}/status")
    public Result<Map<String, Object>> getStatus(@PathVariable String sessionId) {
        return Result.success(uploadService.getUploadStatus(sessionId));
    }

    /**
     * Step 3: Complete the upload.
     * Verifies all chunks, calls MinIO completeMultipartUpload, triggers async parsing.
     */
    @PostMapping("/{sessionId}/complete")
    public Result<Map<String, Object>> completeUpload(
            @PathVariable String sessionId,
            @RequestBody(required = false) Map<String, Object> schemaConfig) {
        return Result.success(uploadService.completeUpload(sessionId, schemaConfig));
    }

    /**
     * Cancel/abort an upload.
     */
    @DeleteMapping("/{sessionId}")
    public Result<Void> abortUpload(@PathVariable String sessionId) {
        uploadService.abortUpload(sessionId);
        return Result.success();
    }
}
```

```java
package com.eval.service;

import com.eval.common.exception.BusinessException;
import io.minio.messages.Part;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetUploadService {

    private final MultipartUploadService multipartService;
    private final UploadTrackingService trackingService;
    private final MinioService minioService; // existing service for bucket name etc.
    // ... other dependencies for dataset creation

    private static final int PART_SIZE = 5 * 1024 * 1024; // 5 MB

    /**
     * Step 1: Initialize upload session.
     */
    public Map<String, Object> initUploadSession(DatasetUploadController.InitUploadRequest req) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        String bucket = "llm-eval"; // from config

        // Generate unique object name
        String ext = "";
        if (req.getFilename() != null && req.getFilename().contains(".")) {
            ext = req.getFilename().substring(req.getFilename().lastIndexOf("."));
        }
        String objectName = "datasets/" + sessionId + ext;

        // Init multipart upload on MinIO
        String uploadId = multipartService.initiateUpload(bucket, objectName);

        // Store metadata in Redis
        trackingService.initSession(sessionId, uploadId, bucket, objectName,
                req.getTotalChunks(), req.getFilename());

        log.info("Upload session created: sessionId={}, totalChunks={}, fileSize={}MB",
                sessionId, req.getTotalChunks(), req.getFileSize() / (1024 * 1024));

        return Map.of(
                "sessionId", sessionId,
                "totalChunks", req.getTotalChunks()
        );
    }

    /**
     * Step 2: Upload a single chunk.
     * Idempotent -- re-uploading the same chunk index is safe.
     */
    public void uploadChunk(String sessionId, int chunkIndex, MultipartFile chunk) {
        Map<Object, Object> meta = trackingService.getSessionMeta(sessionId);
        if (meta.isEmpty()) {
            throw new BusinessException("Upload session not found or expired");
        }

        // Check if already uploaded (idempotent)
        if (trackingService.isPartUploaded(sessionId, chunkIndex + 1)) {
            log.debug("Chunk {} already uploaded, skipping", chunkIndex);
            return;
        }

        String bucket = (String) meta.get("bucket");
        String objectName = (String) meta.get("objectName");
        String uploadId = (String) meta.get("uploadId");

        // Upload part to MinIO
        try (InputStream is = chunk.getInputStream()) {
            Part part = multipartService.uploadPart(
                    bucket, objectName, uploadId,
                    chunkIndex + 1, // MinIO part numbers are 1-indexed
                    is, chunk.getSize()
            );

            // Mark as completed in Redis bitmap
            trackingService.markPartUploaded(sessionId, chunkIndex + 1);

            log.debug("Chunk {} uploaded successfully, progress={}%",
                    chunkIndex, trackingService.getProgress(sessionId));
        } catch (Exception e) {
            log.error("Failed to upload chunk {}", chunkIndex, e);
            throw new BusinessException("Chunk upload failed: " + e.getMessage());
        }
    }

    /**
     * Get upload status for resume.
     */
    public Map<String, Object> getUploadStatus(String sessionId) {
        Map<Object, Object> meta = trackingService.getSessionMeta(sessionId);
        if (meta.isEmpty()) {
            throw new BusinessException("Upload session not found or expired");
        }

        long uploaded = trackingService.getUploadedCount(sessionId);
        int totalParts = Integer.parseInt((String) meta.get("totalParts"));
        double progress = trackingService.getProgress(sessionId);

        return Map.of(
                "sessionId", sessionId,
                "uploadedChunks", uploaded,
                "totalChunks", totalParts,
                "progress", progress,
                "isComplete", uploaded >= totalParts
        );
    }

    /**
     * Step 3: Complete the upload.
     * Verifies all parts, assembles on MinIO, triggers async dataset parsing.
     */
    public Map<String, Object> completeUpload(String sessionId, Map<String, Object> schemaConfig) {
        Map<Object, Object> meta = trackingService.getSessionMeta(sessionId);
        if (meta.isEmpty()) {
            throw new BusinessException("Upload session not found or expired");
        }

        String bucket = (String) meta.get("bucket");
        String objectName = (String) meta.get("objectName");
        String uploadId = (String) meta.get("uploadId");
        int totalParts = Integer.parseInt((String) meta.get("totalParts"));

        // Verify all parts uploaded
        long uploadedCount = trackingService.getUploadedCount(sessionId);
        if (uploadedCount < totalParts) {
            throw new BusinessException(
                    String.format("Upload incomplete: %d/%d parts uploaded",
                            uploadedCount, totalParts));
        }

        // Build parts list in order (required by MinIO)
        List<Part> parts = new ArrayList<>();
        for (int i = 1; i <= totalParts; i++) {
            // We need the ETag for each part. Two options:
            // Option A: Store ETags in Redis during upload (recommended)
            // Option B: Use listParts() API to get them from MinIO
            // For simplicity, use listParts() here:
        }

        // Alternative: retrieve part info from MinIO
        try {
            ListPartsResponse listResp = minioClient.listParts(
                    ListPartsArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .uploadId(uploadId)
                            .build()
            );

            parts = listResp.result().partList().stream()
                    .map(p -> Part.builder()
                            .partNumber(p.partNumber())
                            .etag(p.etag())
                            .build())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new BusinessException("Failed to list parts from MinIO: " + e.getMessage());
        }

        // Complete multipart upload on MinIO
        multipartService.completeUpload(bucket, objectName, uploadId, parts);

        // Trigger async dataset parsing
        String originalFilename = (String) meta.get("originalFilename");
        parseDatasetAsync(sessionId, bucket, objectName, originalFilename, schemaConfig);

        // Cleanup Redis
        trackingService.cleanup(sessionId);

        return Map.of(
                "sessionId", sessionId,
                "status", "processing",
                "message", "Upload complete, dataset parsing in progress"
        );
    }

    /**
     * Abort upload: cancel MinIO multipart upload + cleanup Redis.
     */
    public void abortUpload(String sessionId) {
        Map<Object, Object> meta = trackingService.getSessionMeta(sessionId);
        if (!meta.isEmpty()) {
            multipartService.abortUpload(
                    (String) meta.get("bucket"),
                    (String) meta.get("objectName"),
                    (String) meta.get("uploadId")
            );
            trackingService.cleanup(sessionId);
        }
    }

    /**
     * Async dataset parsing after upload completes.
     * Uses streaming parser to avoid memory issues with 100K rows.
     */
    @Async
    public void parseDatasetAsync(String sessionId, String bucket, String objectName,
                                  String originalFilename, Map<String, Object> schemaConfig) {
        try {
            // Get InputStream from MinIO
            InputStream is = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .build()
            );

            String ext = originalFilename != null ? originalFilename.toLowerCase() : "";
            if (ext.endsWith(".csv")) {
                streamingParseCsv(is, schemaConfig);
            } else if (ext.endsWith(".xlsx")) {
                streamingParseXlsx(is, schemaConfig);
            } else {
                streamingParseJson(is, schemaConfig);
            }

            is.close();
            log.info("Dataset parsing completed: sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("Dataset parsing failed: sessionId={}", sessionId, e);
        }
    }

    /**
     * Streaming CSV parse + batch DB insert.
     * Processes rows in batches of 500 to balance memory vs DB round-trips.
     */
    private void streamingParseCsv(InputStream is, Map<String, Object> schemaConfig) {
        Map<String, String> fieldRoleMap = extractFieldRoleMap(schemaConfig);
        List<com.eval.model.entity.EvalDatasetItem> batch = new ArrayList<>(500);
        final int[] rowIdx = {0};

        StreamingCsvParser.parse(is, (header, rowIndex, values) -> {
            // Build map from header+values
            Map<String, String> rowMap = new LinkedHashMap<>();
            for (int j = 0; j < header.length; j++) {
                rowMap.put(header[j].trim(),
                        j < values.length ? values[j].trim() : "");
            }
            com.eval.model.entity.EvalDatasetItem item = buildItemFromFlatMap(rowMap, fieldRoleMap);
            if (!isItemEmpty(item)) {
                batch.add(item);
            }

            // Batch insert every 500 rows
            if (batch.size() >= 500) {
                batchInsert(batch);
                batch.clear();
            }
            rowIdx[0] = rowIndex;
        });

        // Flush remaining
        if (!batch.isEmpty()) {
            batchInsert(batch);
        }
        log.info("CSV streaming parse complete: {} rows processed", rowIdx[0]);
    }

    /**
     * Streaming XLSX parse + batch DB insert.
     */
    private void streamingParseXlsx(InputStream is, Map<String, Object> schemaConfig) {
        Map<String, String> fieldRoleMap = extractFieldRoleMap(schemaConfig);
        List<com.eval.model.entity.EvalDatasetItem> batch = new ArrayList<>(500);

        StreamingXlsxParser.parse(is, (rowNum, values) -> {
            if (rowNum == 0) return; // skip header

            // values[0..n] are the cells in order
            // Need header from row 0 -- cache it
            // (In practice, store header from first call)
            // This is simplified; real code would cache the header array
        });

        // Batch insert remaining
        if (!batch.isEmpty()) {
            batchInsert(batch);
        }
    }

    /**
     * Batch insert dataset items to DB.
     * Uses MyBatis-Plus saveBatch for efficiency.
     */
    private void batchInsert(List<com.eval.model.entity.EvalDatasetItem> items) {
        // Use MyBatis-Plus batch insert
        // datasetItemMapper.insertBatchSomeColumn(items);
        // Or iterate with individual inserts if batch not available
        for (com.eval.model.entity.EvalDatasetItem item : items) {
            datasetItemMapper.insert(item);
        }
    }
}
```

### 4c. Alternative: Presigned URL approach (frontend -> MinIO directly)

When MinIO is accessible from the browser, skip the backend proxy for data transfer:

```java
// In MultipartUploadService -- generate presigned URLs for all parts
public List<String> presignAllParts(String sessionId, int chunkSize) {
    Map<Object, Object> meta = trackingService.getSessionMeta(sessionId);
    String bucket = (String) meta.get("bucket");
    String objectName = (String) meta.get("objectName");
    String uploadId = (String) meta.get("uploadId");
    long fileSize = Long.parseLong((String) meta.getOrDefault("fileSize", "0"));
    int totalParts = Integer.parseInt((String) meta.get("totalParts"));

    List<String> urls = new ArrayList<>();
    for (int i = 1; i <= totalParts; i++) {
        String url = minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.PUT)
                        .bucket(bucket)
                        .object(objectName)
                        .expiry(3600) // 1 hour
                        .extraQueryParams(Map.of(
                                "uploadId", uploadId,
                                "partNumber", String.valueOf(i)
                        ))
                        .build()
        );
        urls.add(url);
    }
    return urls;
}
```

Frontend uses these URLs to PUT chunks directly to MinIO:

```javascript
// Direct upload to MinIO via presigned URL
const chunk = file.slice(start, end);
await fetch(presignedUrls[chunkIndex], {
  method: 'PUT',
  body: chunk,
  headers: { 'Content-Type': 'application/octet-stream' }
});
// Then notify backend: POST /api/datasets/upload/{sessionId}/chunk-notify
```

---

## 5. Configuration

### application.yml additions

```yaml
minio:
  endpoint: http://localhost:9000
  access-key: minioadmin
  secret-key: minioadmin
  bucket-name: llm-eval

spring:
  redis:
    host: localhost
    port: 6379
  servlet:
    multipart:
      max-file-size: 500MB
      max-request-size: 500MB

# Upload settings
upload:
  chunk-size: 5242880  # 5 MB
  max-file-size: 53687091200  # 50 GB (theoretical max with multipart)
  session-ttl-hours: 24
```

### Maven dependencies to add

```xml
<!-- Redis (if not already present) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>

<!-- OpenCSV (for streaming CSV) -->
<dependency>
    <groupId>com.opencsv</groupId>
    <artifactId>opencsv</artifactId>
    <version>5.9</version>
</dependency>

<!-- POI already at 5.2.5 -- no change needed -->
```

---

## 6. Flow Diagram

```
Frontend (Vue3)                          Backend (Spring Boot)                 MinIO              Redis
     |                                        |                                  |                  |
     |-- POST /upload/init ------------------>|                                  |                  |
     |                                        |-- createMultipartUpload -------->|                  |
     |                                        |<-- uploadId --------------------|                  |
     |                                        |-- initSession (bitmap clear) ---|----------------->|
     |<-- { sessionId, totalChunks } ---------|                                  |                  |
     |                                        |                                  |                  |
     |-- POST /upload/{sid}/chunk/0 --------->|                                  |                  |
     |                                        |-- uploadPart(part=1) ----------->|                  |
     |                                        |<-- etag ------------------------|                  |
     |                                        |-- SETBIT parts 0 1 -------------|----------------->|
     |<-- 200 OK -----------------------------|                                  |                  |
     |                                        |                                  |                  |
     |  ... (repeat for each chunk) ...       |                                  |                  |
     |                                        |                                  |                  |
     |-- GET /upload/{sid}/status ----------->|                                  |                  |
     |                                        |-- BITCOUNT parts ----------------|----------------->|
     |<-- { progress: 73% } -----------------|                                  |                  |
     |                                        |                                  |                  |
     |  ... (resume: skip completed chunks)   |                                  |                  |
     |                                        |                                  |                  |
     |-- POST /upload/{sid}/complete -------->|                                  |                  |
     |                                        |-- BITCOUNT == totalParts? ------>|                  |
     |                                        |-- listParts (get etags) -------->|                  |
     |                                        |-- completeMultipartUpload ------>|                  |
     |                                        |-- cleanup session -------------->|                  |
     |                                        |                                  |                  |
     |                                        |-- @Async: getObject + parse     |                  |
     |                                        |   (streaming CSV/XLSX)          |                  |
     |                                        |   (batch INSERT 500 rows)       |                  |
     |<-- { status: "processing" } -----------|                                  |                  |
```

---

## 7. Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Chunk size | 5 MB | MinIO minimum; good balance for text-heavy dataset files |
| Bitmap vs Set | Bitmap | 12.5 KB vs 3-5 MB for 100K parts |
| Presigned URLs vs proxy | Support both | Presigned for public MinIO, proxy for private |
| Streaming parser | OpenCSV + POI SAX | Memory: ~2 MB vs ~500 MB for 100K rows |
| DB insert strategy | Batch 500 | Balances transaction overhead vs memory |
| Session TTL | 24 hours | Enough for large uploads; auto-cleanup |
| Part number indexing | 1-indexed (matching MinIO) | Redis bitmap uses 0-indexed offset (N-1) |
| Idempotency | Check bitmap before upload | Re-uploading same chunk is safe, no duplicate data |
| Cleanup | Redis TTL + abort on cancel | Prevents orphaned MinIO parts |

---

## 8. Edge Cases and Error Handling

**Resume after browser crash:**
1. User reopens page, frontend calls `GET /upload/{sessionId}/status`
2. Response shows `uploadedChunks: 73000, totalChunks: 100000`
3. Frontend resumes from chunk 73000

**MinIO part failure mid-upload:**
1. `uploadChunk()` catches exception, does NOT call `markPartUploaded()`
2. Redis bitmap shows that part as 0
3. Frontend retries the same chunk

**Session expired (Redis TTL):**
1. `getSessionMeta()` returns empty map
2. Backend returns 410 Gone
3. Frontend starts a fresh upload session

**MinIO cleanup of orphaned parts:**
Configure MinIO lifecycle policy to auto-abort incomplete multipart uploads older than 7 days:
```json
{
  "Rules": [{
    "ID": "cleanup-incomplete-uploads",
    "Status": "Enabled",
    "AbortIncompleteMultipartUpload": {
      "DaysAfterInitiation": 7
    }
  }]
}
```
