package com.telegramtui.service;

import com.telegramtui.telegram.TelegramClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final TelegramClient client;
    private final Map<Long, Download> downloads = new ConcurrentHashMap<>();

    private static final class Download {
        final boolean moveToDownloads;
        final List<Consumer<String>> callbacks = new CopyOnWriteArrayList<>();

        Download(boolean moveToDownloads, Consumer<String> first) {
            this.moveToDownloads = moveToDownloads;
            this.callbacks.add(first);
        }
    }

    public FileService(TelegramClient client) {
        this.client = client;
    }

    public void downloadFile(long fileId, String knownLocalPath, Consumer<String> onComplete) {
        if (knownLocalPath != null && !knownLocalPath.isBlank()) {
            onComplete.accept(knownLocalPath);
            return;
        }
        if (fileId <= 0) {
            log.warn("downloadFile called with invalid fileId={}", fileId);
            return;
        }
        addCallback(fileId, true, onComplete);
    }

    /**
     * Downloads a TDLib file and reports its on-disk path <b>without</b> moving it to ~/Downloads.
     * Used for sticker thumbnails that must stay in TDLib's own cache.
     */
    public void downloadFileInPlace(long fileId, Consumer<String> onComplete) {
        if (fileId <= 0) {
            log.warn("downloadFileInPlace called with invalid fileId={}", fileId);
            return;
        }
        addCallback(fileId, false, onComplete);
    }

    private void addCallback(long fileId, boolean moveToDownloads, Consumer<String> onComplete) {
        Download existing = downloads.putIfAbsent(fileId, new Download(moveToDownloads, onComplete));
        if (existing != null) {
            existing.callbacks.add(onComplete);
        } else {
            sendDownload(fileId);
        }
    }

    private void sendDownload(long fileId) {
        client.send(
                "{\"@type\":\"downloadFile\",\"file_id\":" + fileId
                        + ",\"priority\":1,\"synchronous\":false}",
                result -> {
                    if ("error".equals(TelegramClient.getString(result, "@type"))) {
                        log.warn("Download error for fileId={}: {}", fileId,
                                TelegramClient.getString(result, "message"));
                        downloads.remove(fileId);
                        return;
                    }
                    String path = extractLocalPathFromJson(result);
                    if (!path.isEmpty() && result.contains("\"is_downloading_completed\":true")) {
                        complete(fileId, path);
                    }
                });
    }

    public void onUpdateFile(String json) {
        long fileId = extractFileIdFromUpdate(json);
        if (fileId <= 0) return;
        if (!json.contains("\"is_downloading_completed\":true")) return;
        String path = extractLocalPathFromJson(json);
        if (path.isEmpty()) return;
        complete(fileId, path);
    }

    private void complete(long fileId, String tdlibPath) {
        Download d = downloads.remove(fileId);
        if (d == null) return;
        String result = d.moveToDownloads ? moveToDownloads(tdlibPath) : tdlibPath;
        for (Consumer<String> cb : d.callbacks) {
            cb.accept(result);
        }
    }

    private String moveToDownloads(String tdlibPath) {
        try {
            Path src = Path.of(tdlibPath);
            Path dest = Path.of(System.getProperty("user.home"), "Downloads", src.getFileName().toString());
            Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING);
            return dest.toString();
        } catch (Exception e) {
            log.warn("Failed to move {} to Downloads: {}", tdlibPath, e.getMessage());
            return tdlibPath;
        }
    }

    private long extractFileIdFromUpdate(String json) {
        // TDLib sends {"@type":"updateFile","file":{"@type":"file","id":N,...}}
        // so we find "file":{ first, then look for "id": inside it
        int fileStart = json.indexOf("\"file\":{");
        if (fileStart < 0) return 0;
        int searchFrom = fileStart + "\"file\":{".length();
        int idIdx = json.indexOf("\"id\":", searchFrom);
        if (idIdx < 0) return 0;
        idIdx += "\"id\":".length();
        int end = json.indexOf(',', idIdx);
        if (end < 0) end = json.indexOf('}', idIdx);
        if (end < 0) return 0;
        try {
            return Long.parseLong(json.substring(idIdx, end).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String extractLocalPathFromJson(String json) {
        String marker = "\"path\":\"";
        int idx = json.indexOf(marker);
        if (idx < 0) return "";
        idx += marker.length();
        StringBuilder sb = new StringBuilder();
        while (idx < json.length()) {
            char c = json.charAt(idx);
            if (c == '"') break;
            if (c == '\\' && idx + 1 < json.length()) {
                idx++;
                sb.append(json.charAt(idx));
            } else {
                sb.append(c);
            }
            idx++;
        }
        return sb.toString();
    }
}
