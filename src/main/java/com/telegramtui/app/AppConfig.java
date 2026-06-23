package com.telegramtui.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

// Loads the API credentials from ~/.telegramtui/config.properties
public class AppConfig {

	private static final Logger log = LoggerFactory.getLogger(AppConfig.class);
	private static final Path CONFIG_PATH =
			Path.of(System.getProperty("user.home"), ".telegramtui", "config.properties");

	private final int apiId;
	private final String apiHash;
	private final boolean inlineImagesEnabled;

	public AppConfig() {
		Properties props = new Properties();

		// embedded credentials baked in at release time (absent in dev builds)
		try (InputStream in = AppConfig.class.getResourceAsStream("/credentials.properties")) {
			if (in != null) {
				props.load(in);
			}
		} catch (IOException e) {
			log.warn("Could not read embedded credentials.properties", e);
		}

		// user config overrides embedded values, and is the only source in dev builds
		if (Files.exists(CONFIG_PATH)) {
			try (InputStream in = Files.newInputStream(CONFIG_PATH)) {
				props.load(in);
			} catch (IOException e) {
				throw new IllegalStateException("Failed to read config.properties", e);
			}
		}

		String apiIdStr = props.getProperty("api.id", "").trim();
		this.apiId = apiIdStr.isEmpty() ? 0 : Integer.parseInt(apiIdStr);
		this.apiHash = props.getProperty("api.hash", "").trim();
		if (apiId == 0 || apiHash.isEmpty()) {
			printSetupInstructions();
			throw new IllegalStateException("api.id or api.hash not configured");
		}

		this.inlineImagesEnabled = resolveInlineImages(props);
	}

	private static void printSetupInstructions() {
		System.err.println("""
				TelegramTUI requires Telegram API credentials.
				1. Go to https://my.telegram.org and log in
				2. Click "API development tools" and create an app
				3. Create the file: ~/.telegramtui/config.properties
				   with the following content:
				     api.id=YOUR_API_ID
				     api.hash=YOUR_API_HASH
				""");
	}

	public int getApiId() {
		return apiId;
	}

	public String getApiHash() {
		return apiHash;
	}

	/**
	 * Resolved flag: inline sticker rendering is active only when explicitly enabled (or
	 * auto-detected from the terminal) <em>and</em> {@code ffmpeg} is available on the PATH.
	 */
	public boolean inlineImagesEnabled() {
		return inlineImagesEnabled;
	}

	// inline.images = auto|on|off. "auto" enables it only on terminals that support the Kitty
	// graphics protocol (xterm-kitty / ghostty / WezTerm). Requires ffmpeg regardless.
	private boolean resolveInlineImages(Properties props) {
		String raw = props.getProperty("inline.images", "auto").trim().toLowerCase();
		boolean requested = switch (raw) {
			case "on", "true", "yes", "1" -> true;
			case "off", "false", "no", "0" -> false;
			default -> isKittyCapableTerm();
		};
		if (!requested) return false;
		if (!isFfmpegOnPath()) {
			log.warn("inline.images enabled but ffmpeg not found on PATH — disabling sticker images");
			return false;
		}
		return true;
	}

	private static boolean isKittyCapableTerm() {
		Map<String, String> env = System.getenv();
		String term = env.getOrDefault("TERM", "");
		String termProgram = env.getOrDefault("TERM_PROGRAM", "");
		return "xterm-kitty".equals(term)
				|| "ghostty".equals(term)
				|| "ghostty".equals(termProgram)
				|| "kitty".equals(termProgram)
				|| "WezTerm".equals(termProgram);
	}

	private static boolean isFfmpegOnPath() {
		try {
			Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
			p.getInputStream().transferTo(OutputStream.nullOutputStream());
			if (!p.waitFor(5, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return false;
			}
			return p.exitValue() == 0;
		} catch (Exception e) {
			return false;
		}
	}
}
