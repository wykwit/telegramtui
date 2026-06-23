package com.telegramtui.ui.common;

import com.googlecode.lanterna.terminal.Terminal;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.telegramtui.model.StickerPlacement;

/**
 * Emits the Kitty graphics protocol over the underlying Lanterna terminal so that PNG stickers can
 * be rendered inline in supporting terminals (Kitty, Ghostty, WezTerm).
 *
 * <p>Lanterna's {@code putString} drops non-printable bytes (it routes through
 * {@code TerminalTextUtils.isPrintableCharacter}), which strips the ESC bytes that the protocol
 * needs. So we reflect into {@code StreamBasedTerminal.writeToTerminal(byte[])} — the raw output
 * path Lanterna itself uses — and write escape bytes directly. Lanterna is an unnamed-module
 * classpath jar, so this reflection works on JDK 17+ without {@code --add-opens}.</p>
 *
 * <p>Images are transmitted once (by file path, {@code t=f}) and re-placed each frame using a stable
 * {@code (image id, placement id)} pair, which Kitty atomically moves without flicker. Stickers that
 * scroll out of view are deleted so they do not linger in the scrollback.</p>
 */
public class KittyGraphics {

	private static final String APC_START = "\u001b_G";
	private static final String APC_END = "\u001b\\"; // ESC backslash (String Terminator)

	private final Terminal terminal;
	private final Method writeToTerminal;
	private final boolean available;

	private final Map<Long, Integer> imageIds = new HashMap<>();
	private final Set<Integer> transmitted = new HashSet<>();
	private final Map<Integer, Integer> placedLastFrame = new HashMap<>(); // placementId -> imageId
	private int nextImageId = 1;

	public KittyGraphics(Terminal terminal, boolean enabled) {
		this.terminal = terminal;
		Method resolved = null;
		if (enabled && terminal != null) {
			try {
				Class<?> cls = Class.forName("com.googlecode.lanterna.terminal.ansi.StreamBasedTerminal");
				if (cls.isInstance(terminal)) {
					resolved = cls.getDeclaredMethod("writeToTerminal", byte[].class);
					resolved.setAccessible(true);
				}
			} catch (Exception ignored) {
				resolved = null;
			}
		}
		this.writeToTerminal = resolved;
		this.available = resolved != null;
	}

	public boolean isAvailable() {
		return available;
	}

	/** Clears every image this helper knows about. Call on chat switch or terminal resize. */
	public void reset() {
		if (!available) return;
		synchronized (this) {
			if (transmitted.isEmpty() && placedLastFrame.isEmpty()) return;
			emit(encode("a=d,d=A,q=2", null));
			transmitted.clear();
			placedLastFrame.clear();
			imageIds.clear();
			nextImageId = 1;
			flush();
		}
	}

	/**
	 * Places every sticker whose PNG is ready. {@code pngPathFor} maps a thumbnail file id to its
	 * cached PNG path (or {@code null} if not ready — those are skipped, leaving their reserved
	 * rows showing the emoji placeholder).
	 */
	public void renderFrame(List<StickerPlacement> placements, java.util.function.LongFunction<String> pngPathFor) {
		if (!available) return;
		synchronized (this) {
			Map<Integer, Integer> placedThisFrame = new HashMap<>(); // placementId -> imageId
			List<byte[]> pending = new ArrayList<>();
			int nextPlacementId = 1;

			for (StickerPlacement p : placements) {
				String png = pngPathFor.apply(p.fileId());
				if (png == null) continue;
				int imgId = imageIds.computeIfAbsent(p.fileId(), k -> nextImageId++);
				// Only "r" (rows) is sent; Kitty derives "c" (columns) from the source aspect
				// ratio, so square stickers aren't squished by the ~2:1 terminal cell shape.
				if (!transmitted.contains(imgId)) {
					// transmit only — store under imgId WITHOUT displaying.
					pending.add(encode("a=t,q=2,f=100,t=f,i=" + imgId, png));
					transmitted.add(imgId);
				}
				// each visible sticker occurrence gets its own placement id so that
				// duplicate stickers (same fileId) can coexist on screen independently.
				int pid = nextPlacementId++;
				pending.add(cup(p.row(), p.col()));
				pending.add(encode(
						"a=p,q=2,i=" + imgId + ",p=" + pid + ",r=" + p.rows() + ",C=1",
						null));
				placedThisFrame.put(pid, imgId);
			}

			// delete placements that are no longer visible so they don't leak into scrollback
			for (Map.Entry<Integer, Integer> e : placedLastFrame.entrySet()) {
				if (!placedThisFrame.containsKey(e.getKey())) {
					pending.add(encode("a=d,d=I,q=2,i=" + e.getValue() + ",p=" + e.getKey(), null));
				}
			}

			for (byte[] b : pending) emit(b);
			flush();
			placedLastFrame.clear();
			placedLastFrame.putAll(placedThisFrame);
		}
	}

	/** Cursor Position (CUP) — 1-based row;col. Maps Lanterna's 0-based cell to the terminal cell. */
	private byte[] cup(int row, int col) {
		return ("\u001b[" + (row + 1) + ";" + (col + 1) + "H").getBytes(StandardCharsets.UTF_8);
	}

	private byte[] encode(String control, String pngPath) {
		if (pngPath == null) {
			return (APC_START + control + APC_END).getBytes(StandardCharsets.UTF_8);
		}
		String payload = Base64.getEncoder().encodeToString(pngPath.getBytes(StandardCharsets.UTF_8));
		return (APC_START + control + ";" + payload + APC_END).getBytes(StandardCharsets.UTF_8);
	}

	private void emit(byte[] bytes) {
		try {
			writeToTerminal.invoke(terminal, (Object) bytes);
		} catch (Exception e) {
			// never let image rendering break the UI
		}
	}

	private void flush() {
		try {
			terminal.flush();
		} catch (IOException e) {
			// ignore
		}
	}
}
