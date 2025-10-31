# sente-lite Compression Feature

**Date Created**: 2025-10-31
**Status**: Not yet implemented (design phase)

## Overview

This document analyzes compression options for sente-lite WebSocket messaging, considering both Babashka (BB) and browser environments.

## Compression Options Analysis

### 1. gzip (RFC 1952)

**Pros:**
- ✅ Universal support - available everywhere (JVM, BB, browsers)
- ✅ Good compression ratio (~60-70% for text/EDN)
- ✅ Native Java support (`java.util.zip.GZIPInputStream/GZIPOutputStream`)
- ✅ Browser has native `CompressionStream`/`DecompressionStream` (modern browsers)
- ✅ Battle-tested, reliable

**Cons:**
- ❌ Slower than LZ4/Zstandard
- ❌ Medium CPU usage
- ❌ Not ideal for tiny messages (<1KB) - overhead negates benefits

**BB/Browser availability:**
- BB: ✅ Built-in via `java.util.zip`
- Browser: ✅ Native via `CompressionStream('gzip')` (Chrome 80+, Firefox 113+, Safari 16.4+)

### 2. Brotli (RFC 7932)

**Pros:**
- ✅ Better compression than gzip (~20% better)
- ✅ Designed for HTTP/web use
- ✅ Browser native support

**Cons:**
- ❌ Slower compression than gzip
- ❌ No native Java/BB support (would need external library)
- ❌ More complex implementation
- ❌ Primarily HTTP-focused (Content-Encoding header)

**BB/Browser availability:**
- BB: ❌ Needs external library (e.g., Brotli4j)
- Browser: ✅ Native via `CompressionStream('br')`

### 3. Deflate (RFC 1951)

**Pros:**
- ✅ Native Java support
- ✅ Simpler than gzip (gzip = deflate + header/checksum)
- ✅ Slightly faster than gzip

**Cons:**
- ❌ Less portable than gzip
- ❌ Similar compression ratio to gzip
- ❌ Browser support less clear than gzip

**BB/Browser availability:**
- BB: ✅ Built-in via `java.util.zip.Deflater/Inflater`
- Browser: ✅ Native via `CompressionStream('deflate')`

### 4. LZ4

**Pros:**
- ✅ Extremely fast (compression/decompression)
- ✅ Low CPU usage
- ✅ Good for real-time messaging
- ✅ Better for small messages

**Cons:**
- ❌ Lower compression ratio (~50% vs gzip's 70%)
- ❌ No native Java/BB support (needs library: lz4-java)
- ❌ No browser native support (needs JS library)
- ❌ Additional dependencies

**BB/Browser availability:**
- BB: ❌ Needs library (lz4-java)
- Browser: ❌ Needs JS library (lz4js)

### 5. Zstandard (zstd)

**Pros:**
- ✅ Modern, excellent balance (speed + ratio)
- ✅ Adjustable compression levels
- ✅ Better than gzip in most scenarios
- ✅ Used by Facebook, Linux kernel

**Cons:**
- ❌ No native Java/BB support (needs library: zstd-jni)
- ❌ No browser native support (needs JS library)
- ❌ Additional dependencies

**BB/Browser availability:**
- BB: ❌ Needs library (zstd-jni)
- Browser: ❌ Needs JS library

## Recommendation

### Phase 1: Start with gzip only

**Why:**
- Zero dependencies, native support everywhere
- Simple implementation using `java.util.zip` in BB, `CompressionStream` in browser
- Always support `:none` (no compression) as fallback

### Phase 2 (optional): Add deflate

**Why:**
- Same benefits as gzip but slightly simpler
- Also native everywhere
- Marginally faster

### Don't implement (for now):

- ❌ **Brotli** - Requires external libs, mainly HTTP-focused
- ❌ **LZ4** - Requires external libs on both sides
- ❌ **Zstd** - Requires external libs on both sides

## Practical Considerations

### When compression helps:
- Messages > 1KB (overhead matters for small messages)
- EDN/JSON text data (highly compressible)
- Repeated structures/patterns

### When compression hurts:
- Tiny messages (<500 bytes) - overhead > savings
- Binary data already compressed
- CPU-constrained environments

### Smart implementation strategies:
- Negotiate compression during handshake
- Auto-disable for small messages (<1KB threshold)
- Allow per-message override
- Default: `:none` for compatibility

## Suggested Capability Negotiation

```clojure
{:capabilities
 {:compression #{:gzip :deflate :none}  ; Start with these
  :compression-threshold 1024}}         ; Don't compress < 1KB
```

## Implementation Example (BB side)

```clojure
(ns sente-lite.compression
  (:import [java.util.zip GZIPOutputStream GZIPInputStream]
           [java.io ByteArrayOutputStream ByteArrayInputStream]))

(defn gzip-compress
  "Compress data using gzip. Returns byte array."
  [^bytes data]
  (let [baos (ByteArrayOutputStream.)
        gzip (GZIPOutputStream. baos)]
    (.write gzip data)
    (.close gzip)
    (.toByteArray baos)))

(defn gzip-decompress
  "Decompress gzip data. Returns byte array."
  [^bytes data]
  (let [bais (ByteArrayInputStream. data)
        gzip (GZIPInputStream. bais)
        baos (ByteArrayOutputStream.)]
    (loop []
      (let [b (.read gzip)]
        (when (not= b -1)
          (.write baos b)
          (recur))))
    (.close gzip)
    (.toByteArray baos)))
```

## Implementation Example (Browser side)

```clojure
(ns sente-lite.compression-browser)

(defn gzip-compress
  "Compress data using browser CompressionStream API.
  Returns Promise<Uint8Array>."
  [data]
  (let [stream (js/CompressionStream. "gzip")
        blob (js/Blob. #js [data])
        compressed-stream (.pipeThrough (.stream blob) stream)]
    (-> (.arrayBuffer (js/Response. compressed-stream))
        (.then #(js/Uint8Array. %)))))

(defn gzip-decompress
  "Decompress gzip data using browser DecompressionStream API.
  Returns Promise<Uint8Array>."
  [data]
  (let [stream (js/DecompressionStream. "gzip")
        blob (js/Blob. #js [data])
        decompressed-stream (.pipeThrough (.stream blob) stream)]
    (-> (.arrayBuffer (js/Response. decompressed-stream))
        (.then #(js/Uint8Array. %)))))
```

## Message Format with Compression

### Wire format option 1: Flag in message envelope

```clojure
;; Compressed message
[:sente/msg {:compressed? true
             :algorithm :gzip
             :original-size 5000
             :data <compressed-bytes>}]

;; Uncompressed message
[:sente/msg {:compressed? false
             :data <raw-data>}]
```

### Wire format option 2: Separate message type

```clojure
;; Compressed
[:sente/msg-compressed {:algorithm :gzip
                        :original-size 5000
                        :data <compressed-bytes>}]

;; Uncompressed (existing format)
[:sente/msg {:data <raw-data>}]
```

## Negotiation Flow

```clojure
;; 1. Client sends capabilities on connect
[:sente/handshake {:capabilities {:compression #{:gzip :none}
                                  :compression-threshold 1024}}]

;; 2. Server responds with mutual capabilities
[:sente/handshake-ack {:capabilities {:compression #{:gzip}  ; intersection
                                      :compression-threshold 1024}}]

;; 3. Both sides now know to use gzip for messages > 1KB
```

## Performance Considerations

### Threshold analysis:

| Message Size | Compression Overhead | Recommended Action |
|--------------|---------------------|-------------------|
| < 500 bytes  | Overhead > savings  | Never compress    |
| 500-1000 bytes | Marginal benefit  | Optional          |
| > 1KB        | Clear benefit       | Compress          |
| > 10KB       | Major benefit       | Always compress   |

### CPU vs Bandwidth tradeoff:

- **High bandwidth, low CPU**: Prefer `:none`
- **Low bandwidth, high CPU**: Prefer `:gzip`
- **Balanced**: Use threshold-based compression (1KB default)

## Browser Compatibility

### CompressionStream API support:

- ✅ Chrome 80+ (Feb 2020)
- ✅ Edge 80+ (Feb 2020)
- ✅ Firefox 113+ (May 2023)
- ✅ Safari 16.4+ (Mar 2023)

**Fallback strategy:** If `CompressionStream` unavailable, negotiate `:none` only.

```clojure
(def compression-available?
  (and (exists? js/CompressionStream)
       (exists? js/DecompressionStream)))

(def capabilities
  (if compression-available?
    {:compression #{:gzip :none}}
    {:compression #{:none}}))
```

## Testing Strategy

### Unit tests:
1. Compress/decompress round-trip (BB and browser)
2. Cross-platform compatibility (BB compress → browser decompress, vice versa)
3. Threshold behavior (don't compress small messages)
4. Negotiation flow
5. Fallback to `:none` when compression unavailable

### Integration tests:
1. Full message flow with compression
2. Performance benchmarks (size reduction, CPU time)
3. Error handling (corrupted compressed data)

## Future Enhancements

### Possible additions (low priority):
- Adaptive threshold based on network conditions
- Per-user-id compression settings
- Compression statistics/monitoring
- Support for deflate (after gzip proven)

### Not recommended:
- External library dependencies (LZ4, zstd, brotli)
- Complex compression algorithms
- Per-message compression negotiation (too chatty)

## References

- [RFC 1952: GZIP file format specification](https://datatracker.ietf.org/doc/html/rfc1952)
- [RFC 1951: DEFLATE specification](https://datatracker.ietf.org/doc/html/rfc1951)
- [MDN: CompressionStream](https://developer.mozilla.org/en-US/docs/Web/API/CompressionStream)
- [Compression Streams Living Standard](https://compression.spec.whatwg.org/)

## Decision

**Start with**: gzip + none, threshold at 1KB
**Reason**: Zero dependencies, maximum compatibility, simple implementation
**Timeline**: Implement after capability negotiation system is in place

---

**Bottom line:** Start with **gzip + none**, add deflate if needed. Skip external dependencies (brotli, lz4, zstd) unless there's compelling evidence they're needed.
