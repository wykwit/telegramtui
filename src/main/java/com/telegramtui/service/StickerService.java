package com.telegramtui.service;

import com.telegramtui.telegram.TelegramClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Downloads Telegram sticker thumbnails via TDLib and transcodes them to PNG using system
 * {@code ffmpeg}. The PNG is cached at {@code ~/.telegramtui/stickers/<thumbnailFileId>.png}.
 *
 * <p>The service is fully asynchronous: callers ask it to "ensure" a sticker is ready and poll
 * {@link #getCachedPngPath(long)} each frame. When a PNG becomes available, {@link #getReadyVersion()}
 * is bumped so the UI knows to redraw and place the image.</p>
 */
public class StickerService {

	private static final Logger log = LoggerFactory.getLogger(StickerService.class);

	private final TelegramClient client;
	private final FileService fileService;
	private final Path cacheDir;
	private final ExecutorService worker;

	private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
	private final AtomicLong imageVersion = new AtomicLong(0);

	public StickerService(TelegramClient client, FileService fileService) {
		this.client = client;
		this.fileService = fileService;
		this.cacheDir = Path.of(System.getProperty("user.home"), ".telegramtui", "stickers");
		this.worker = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "sticker-worker");
			t.setDaemon(true);
			return t;
		});
		try {
			Files.createDirectories(cacheDir);
		} catch (IOException e) {
			log.warn("Could not create sticker cache dir {}: {}", cacheDir, e.getMessage());
		}
	}

	/** Monotonic counter bumped whenever a new PNG becomes available — UI redraws on change. */
	public long getReadyVersion() {
		return imageVersion.get();
	}

	/** Returns the cached PNG path for a thumbnail file id, or {@code null} if not ready yet. */
	public String getCachedPngPath(long thumbFileId) {
		if (thumbFileId <= 0) return null;
		Path png = cacheDir.resolve(thumbFileId + ".png");
		return Files.isRegularFile(png) ? png.toString() : null;
	}

	/**
	 * Ensures the sticker thumbnail is downloaded and transcoded. Idempotent: safe to call every
	 * frame for every visible sticker. When done, {@link #getReadyVersion()} is bumped.
	 */
	public void ensureSticker(long thumbFileId) {
		if (thumbFileId <= 0) return;
		if (getCachedPngPath(thumbFileId) != null) return;
		if (!inFlight.add(thumbFileId)) return;
		worker.execute(() -> {
			try {
				String src = downloadBlocking(thumbFileId);
				if (src == null) return;
				Path png = cacheDir.resolve(thumbFileId + ".png");
				if (ffmpegTranscode(src, png)) {
					imageVersion.incrementAndGet();
				}
			} catch (Exception e) {
				log.warn("Sticker prepare failed for {}: {}", thumbFileId, e.getMessage());
			} finally {
				inFlight.remove(thumbFileId);
			}
		});
	}

	private String downloadBlocking(long fileId) {
		CountDownLatch latch = new CountDownLatch(1);
		String[] holder = new String[1];
		fileService.downloadFileInPlace(fileId, path -> {
			holder[0] = path;
			latch.countDown();
		});
		try {
			if (!latch.await(30, TimeUnit.SECONDS)) {
				log.warn("Sticker thumbnail download timed out for fileId={}", fileId);
				return null;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
		return holder[0];
	}

	private boolean ffmpegTranscode(String src, Path destPng) {
		try {
			ProcessBuilder pb = new ProcessBuilder(
					"ffmpeg", "-y", "-loglevel", "error", "-i", src, destPng.toString());
			pb.redirectErrorStream(true);
			Process p = pb.start();
			p.getInputStream().transferTo(OutputStream.nullOutputStream());
			if (!p.waitFor(10, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				log.warn("ffmpeg timed out converting {}", src);
				return false;
			}
			int code = p.exitValue();
			if (code == 0 && Files.isRegularFile(destPng) && Files.size(destPng) > 0) return true;
			log.warn("ffmpeg exited with code {} converting {}", code, src);
			return false;
		} catch (Exception e) {
			log.warn("ffmpeg failed converting {}: {}", src, e.getMessage());
			return false;
		}
	}
}
