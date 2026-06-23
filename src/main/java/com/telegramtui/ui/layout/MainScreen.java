package com.telegramtui.ui.layout;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import com.telegramtui.service.ChatService;
import com.telegramtui.service.FileService;
import com.telegramtui.service.FolderService;
import com.telegramtui.service.MessageService;
import com.telegramtui.service.StickerService;
import com.telegramtui.telegram.TelegramClient;
import com.telegramtui.model.StickerPlacement;
import com.telegramtui.ui.chat.ConversationPanel;
import com.telegramtui.ui.common.KittyGraphics;
import com.telegramtui.ui.popup.CommandPopup;
import com.telegramtui.ui.sidebar.SidebarPanel;

import java.io.IOException;
import java.util.List;

public class MainScreen {

	private static final long REDRAW_MS = 200;

	private final TelegramClient client;
	private final MessageService messageService;
	private final StickerService stickerService;
	private final boolean inlineImages;
	private final SidebarPanel sidebarPanel;
	private final ConversationPanel conversationPanel;
	private final FocusManager focusManager = new FocusManager();
	private final CommandPopup commandPopup;
	private final MainInputRouter inputRouter;

	public MainScreen(TelegramClient client, ChatService chatService,
	                  FolderService folderService, MessageService messageService,
	                  FileService fileService, StickerService stickerService, boolean inlineImages) {
		this.client = client;
		this.messageService = messageService;
		this.stickerService = stickerService;
		this.inlineImages = inlineImages;
		this.sidebarPanel = new SidebarPanel(chatService, folderService);
		this.conversationPanel = new ConversationPanel(messageService, chatService, fileService, inlineImages);
		this.commandPopup = new CommandPopup(chatService, messageService);
		this.inputRouter = new MainInputRouter(commandPopup, conversationPanel, focusManager,
				sidebarPanel, chatService);
	}

	public void start() throws IOException, InterruptedException {
		Terminal terminal = new DefaultTerminalFactory().createTerminal();
		var screen = new TerminalScreen(terminal);
		screen.startScreen();

		KittyGraphics kitty = new KittyGraphics(terminal, inlineImages);

		screen.clear();
		drawLayout(screen);
		screen.refresh();
		emitStickerImages(kitty);

		boolean dirty = false;
		long lastRedrawMs = System.currentTimeMillis();
		long lastSeenVersion = messageService.getChangeVersion();
		long lastStickerVersion = stickerService.getReadyVersion();
		long lastActiveChatId = conversationPanel.getActiveChatId();
		boolean lastOverlayActive = false;

		while (true) {
			// Drain all pending keystrokes before redrawing
			boolean gotInput = false;
			MainInputRouter.Action pendingAction = MainInputRouter.Action.CONTINUE;
			KeyStroke key;
			while ((key = screen.pollInput()) != null) {
				pendingAction = inputRouter.route(key);
				gotInput = true;
				dirty = true;
				if (pendingAction != MainInputRouter.Action.CONTINUE) break;
			}
			if (pendingAction == MainInputRouter.Action.LOGOUT) {
				client.send("{\"@type\":\"logOut\"}", null);
				break;
			}
			if (pendingAction == MainInputRouter.Action.QUIT) break;
			if (pendingAction == MainInputRouter.Action.TOGGLE_FOCUS) {
				LayoutManager.setFocusMode(!LayoutManager.isFocusMode());
				screen.clear();
				kitty.reset();
				dirty = true;
			}

			long currentVersion = messageService.getChangeVersion();
			if (currentVersion != lastSeenVersion) {
				lastSeenVersion = currentVersion;
				dirty = true;
			}

			long currentStickerVersion = stickerService.getReadyVersion();
			if (currentStickerVersion != lastStickerVersion) {
				lastStickerVersion = currentStickerVersion;
				dirty = true;
			}

		// chat switch: drop all images from the previous chat so they don't bleed across
		long activeChatId = conversationPanel.getActiveChatId();
		if (activeChatId != lastActiveChatId) {
			lastActiveChatId = activeChatId;
			kitty.reset();
			dirty = true;
		}

		// overlay (command popup / url picker): clear images when it opens so they don't
		// composite over the popup text; re-emit when it closes
		boolean overlayActive = commandPopup.isActive() || conversationPanel.isUrlPickerActive();
		if (overlayActive != lastOverlayActive) {
			lastOverlayActive = overlayActive;
			if (overlayActive) kitty.reset();
			dirty = true;
		}

			long now = System.currentTimeMillis();
			boolean timeElapsed = (now - lastRedrawMs) >= REDRAW_MS;

			TerminalSize newSize = screen.doResizeIfNecessary();
			if (newSize != null) {
				screen.clear();
				drawLayout(screen);
				screen.refresh(Screen.RefreshType.COMPLETE);
				kitty.reset();
				emitStickerImages(kitty);
				lastRedrawMs = now;
				dirty = false;
			} else if (dirty || timeElapsed) {
				drawLayout(screen);
				screen.refresh();
				emitStickerImages(kitty);
				lastRedrawMs = now;
				dirty = false;
			}

			if (!gotInput) Thread.sleep(10);
		}
		kitty.reset();
		screen.stopScreen();
	}

	/**
	 * After Lanterna has flushed the text layer, place any sticker images whose PNGs are ready.
	 * Images live on a separate plane above text, so they must be emitted after refresh in
	 * absolute cell coordinates (which Lanterna and the Kitty protocol both treat as 0-based).
	 * We also kick off downloads for stickers not yet cached.
	 */
	private void emitStickerImages(KittyGraphics kitty) {
		if (!kitty.isAvailable()) return;
		if (commandPopup.isActive() || conversationPanel.isUrlPickerActive()) return;
		List<StickerPlacement> placements = conversationPanel.getStickerPlacements();
		for (StickerPlacement p : placements) {
			stickerService.ensureSticker(p.fileId());
		}
		kitty.renderFrame(placements, stickerService::getCachedPngPath);
	}

	private void drawLayout(Screen screen) {
		// hide the terminal cursor — without this it sits at (0,0) and looks like a highlighted square
		screen.setCursorPosition(null);

		String modeLabel = commandPopup.isActive() ? commandPopup.getModeLabel()
				                   : conversationPanel.getModeHint();

		// clear label before drawing so the box border has no stale text next to the corner
		LayoutManager.getChatBox().setLabel("");
		LayoutManager.draw(screen, client.getUpdateHandler().getConnectionLabel(), focusManager,
				modeLabel);
		TextGraphics g = screen.newTextGraphics();
		if (!LayoutManager.isFocusMode()) {
			sidebarPanel.render(g, LayoutManager.getSidebarBox());
		}
		conversationPanel.render(g, LayoutManager.getChatBox());
		TerminalSize size = screen.getTerminalSize();
		commandPopup.render(g, size.getColumns(), size.getRows());
	}
}
