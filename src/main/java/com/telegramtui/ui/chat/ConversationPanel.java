package com.telegramtui.ui.chat;

import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.telegramtui.model.ChatModel;
import com.telegramtui.model.MessageModel;
import com.telegramtui.model.StickerPlacement;
import com.telegramtui.service.ChatService;
import com.telegramtui.service.FileService;
import com.telegramtui.service.MessageService;
import com.telegramtui.ui.common.Box;
import com.telegramtui.ui.common.CatppuccinMocha;
import com.telegramtui.ui.common.SystemOpen;

import java.util.ArrayList;
import java.util.List;

public class ConversationPanel {

    private final MessageService messageService;
    private final ChatService chatService;
    private final FileService fileService;
    private final boolean inlineImages;
    private final ChatTabBar tabBar = new ChatTabBar();
    private final MessageInputBuffer inputBuffer;

    // sticker image placements for the most recent render, in absolute cell coordinates.
    // Filled by the renderer, consumed by MainScreen to emit Kitty graphics after refresh.
    private final List<StickerPlacement> stickerPlacements = new ArrayList<>();

    // -1 means no message selected (auto-scroll to bottom)
    private int selectedMsgIndex = -1;
    private int viewportTop = 0;
    private long pendingJumpMessageId = 0;

    // url picker overlay state
    private boolean urlPickerActive = false;
    private List<String> urlPickerList = List.of();
    private int urlPickerIndex = 0;

    // short status message shown after a download (disappears after 3 seconds)
    private volatile String actionStatus = "";
    private volatile long actionStatusAt = 0;

    public ConversationPanel(MessageService messageService, ChatService chatService,
                             FileService fileService, boolean inlineImages) {
        this.messageService = messageService;
        this.chatService = chatService;
        this.fileService = fileService;
        this.inlineImages = inlineImages;
        this.inputBuffer = new MessageInputBuffer(messageService);
    }

    public void openChat(ChatModel chat) {
        if (chat == null) return;
        chatService.markRead(chat.id());
        selectedMsgIndex = -1;
        viewportTop = Integer.MAX_VALUE / 2;
        boolean isNew = tabBar.openChat(chat);
        if (isNew) {
            messageService.loadMessages(chat.id());
        } else {
            messageService.markMessagesViewed(chat.id());
        }
    }

    public void closeCurrentTab() {
        tabBar.closeCurrentTab();
    }

    public void closeOtherTabs() {
        tabBar.closeOtherTabs();
    }

    // scrolls to and selects a specific message — loads history around it if not cached yet
    public void jumpToMessage(long msgId) {
        pendingJumpMessageId = msgId;
        tryApplyPendingJump();
        if (pendingJumpMessageId != 0) {
            ChatModel chat = tabBar.activeChat();
            if (chat != null) messageService.loadMessagesAround(chat.id(), msgId);
        }
    }

    public boolean isInInsertMode() {
        return inputBuffer.isInInsertMode();
    }

    public boolean isInlineImages() {
        return inlineImages;
    }

    public boolean isUrlPickerActive() {
        return urlPickerActive;
    }

    /** Sticker placements recorded during the last render, in absolute terminal cell coordinates. */
    public List<StickerPlacement> getStickerPlacements() {
        return stickerPlacements;
    }

    /** The currently active chat id, or {@code -1} if none. Used to detect chat switches. */
    public long getActiveChatId() {
        ChatModel chat = tabBar.activeChat();
        return chat != null ? chat.id() : -1;
    }

    public String getModeHint() {
        if (inputBuffer.isInInsertMode()) return inputBuffer.getModeHint();
        if (urlPickerActive) return "j/k navigate   Enter open   Esc cancel";
        return "";
    }

    // builds the hint shown in the input area: action status, selection hints, or null
    private String buildInputHint() {
        if (inputBuffer.isInInsertMode() || inputBuffer.isDeletePending()) return null;
        if (!actionStatus.isEmpty()
                && (actionStatusAt == 0 || System.currentTimeMillis() - actionStatusAt < 3000)) {
            return actionStatus;
        }
        if (selectedMsgIndex < 0) return null;
        ChatModel chat = tabBar.activeChat();
        if (chat == null) return null;
        List<MessageModel> msgs = messageService.getMessages(chat.id());
        if (selectedMsgIndex >= msgs.size()) return "e:edit  d:delete  r:reply  Enter:deselect";
        MessageModel m = msgs.get(selectedMsgIndex);
        String extra = (m.fileId() > 0 || !m.urls().isEmpty()) ? "  o:open  p:pull" : "";
        return "e:edit  d:delete  r:reply  Enter:deselect" + extra;
    }

    public boolean handleKey(KeyStroke key) {
        ChatModel chat = tabBar.activeChat();

        // url picker takes priority over everything else
        if (urlPickerActive) {
            if (key.getKeyType() == KeyType.Escape) {
                urlPickerActive = false;
            } else if (key.getKeyType() == KeyType.Character) {
                char c = key.getCharacter();
                if (c == 'j') urlPickerIndex = Math.min(urlPickerList.size() - 1, urlPickerIndex + 1);
                else if (c == 'k') urlPickerIndex = Math.max(0, urlPickerIndex - 1);
            } else if (key.getKeyType() == KeyType.Enter) {
                if (urlPickerIndex < urlPickerList.size()) {
                    SystemOpen.open(urlPickerList.get(urlPickerIndex));
                }
                urlPickerActive = false;
            }
            return true;
        }

        // delete confirmation comes before normal input
        if (inputBuffer.isDeletePending()) {
            List<MessageModel> msgs = chat != null
                    ? messageService.getMessages(chat.id()) : List.of();
            boolean deleted = inputBuffer.handleDeleteConfirmation(key, chat, selectedMsgIndex, msgs);
            if (deleted) selectedMsgIndex = -1;
            return true;
        }

        if (inputBuffer.isInInsertMode()) {
            MessageInputBuffer.Result result = inputBuffer.handleInsertKey(key, chat);
            if (result == MessageInputBuffer.Result.SENT) selectedMsgIndex = -1;
            return true;
        }

        // enter/esc deselects the current message
        if (key.getKeyType() == KeyType.Enter || key.getKeyType() == KeyType.Escape) {
            selectedMsgIndex = -1;
            return true;
        }

        // arrow keys mirror j/k message navigation
        if (key.getKeyType() == KeyType.ArrowDown) { moveSelection(chat, 1); return true; }
        if (key.getKeyType() == KeyType.ArrowUp) { moveSelection(chat, -1); return true; }

        if (key.getKeyType() == KeyType.Character) {
            char c = key.getCharacter();

            // j/k navigate messages, J/K jump by 10
            if (c == 'j') { moveSelection(chat, 1); return true; }
            if (c == 'k') { moveSelection(chat, -1); return true; }
            if (c == 'J') { moveSelection(chat, 10); return true; }
            if (c == 'K') { moveSelection(chat, -10); return true; }

            // H/L cycle chat tabs (prev/next) — complements 1-5 and Tab
            if (c == 'H') { tabBar.switchToPrevTab(); return true; }
            if (c == 'L') { tabBar.switchToNextTab(); return true; }

            if (c == 'i') {
                inputBuffer.startInsert();
                return true;
            }

            if (c == 'r' && selectedMsgIndex >= 0 && chat != null) {
                List<MessageModel> msgs = messageService.getMessages(chat.id());
                if (selectedMsgIndex < msgs.size()) {
                    inputBuffer.startReply(msgs.get(selectedMsgIndex));
                }
                return true;
            }

            if (c == 'd' && selectedMsgIndex >= 0) {
                inputBuffer.startDelete();
                return true;
            }

            // edit only works on your own plain-text messages
            if (c == 'e' && selectedMsgIndex >= 0 && chat != null) {
                List<MessageModel> msgs = messageService.getMessages(chat.id());
                if (selectedMsgIndex < msgs.size()) {
                    MessageModel m = msgs.get(selectedMsgIndex);
                    if (!m.isOutgoing()) {
                        flashStatus("Can't edit — not your message");
                    } else if (!isEditable(m)) {
                        flashStatus("Can't edit this message type");
                    } else {
                        inputBuffer.startEdit(m);
                    }
                }
                return true;
            }

            if (c == 'o' && selectedMsgIndex >= 0 && chat != null) {
                List<MessageModel> msgs = messageService.getMessages(chat.id());
                if (selectedMsgIndex < msgs.size()) {
                    MessageModel m = msgs.get(selectedMsgIndex);
                    if (!m.urls().isEmpty()) {
                        // show picker if there are multiple URLs, open directly if just one
                        if (m.urls().size() == 1) {
                            SystemOpen.open(m.urls().get(0));
                        } else {
                            urlPickerList = m.urls();
                            urlPickerIndex = 0;
                            urlPickerActive = true;
                        }
                    } else if (m.fileId() > 0) {
                        actionStatus = "Opening...";
                        actionStatusAt = 0; // no timeout — stays until callback fires
                        fileService.downloadFile(m.fileId(), m.localFilePath(), path -> {
                            SystemOpen.open(path);
                            actionStatus = "Opened";
                            actionStatusAt = System.currentTimeMillis();
                        });
                    }
                }
                return true;
            }

            if (c == 'p' && selectedMsgIndex >= 0 && chat != null) {
                List<MessageModel> msgs = messageService.getMessages(chat.id());
                if (selectedMsgIndex < msgs.size()) {
                    MessageModel m = msgs.get(selectedMsgIndex);
                    if (m.fileId() > 0) {
                        if (!m.localFilePath().isEmpty()) {
                            actionStatus = "Already downloaded: " + m.localFilePath();
                        } else {
                            actionStatus = "Downloading...";
                            actionStatusAt = 0; // no timeout — stays until callback fires
                            fileService.downloadFile(m.fileId(), m.localFilePath(), path -> {
                                actionStatus = "Downloaded: " + path;
                                actionStatusAt = System.currentTimeMillis();
                            });
                        }
                    }
                }
                return true;
            }

            // G — jump to newest message (vim: go to end)
            if (c == 'G') {
                selectedMsgIndex = -1;
                viewportTop = Integer.MAX_VALUE / 2;
                return true;
            }

            // switch tabs with number keys 1-5
            if (c >= '1' && c <= '5') {
                tabBar.switchToTab(c - '1');
                return true;
            }
        }

        // Tab — cycle to next open tab
        if (key.getKeyType() == KeyType.Tab) {
            tabBar.switchToNextTab();
            return true;
        }

        return false;
    }

    // moves the message selection by delta rows, clamped to the message list; when nothing is
    // selected, the first move lands on the newest message (end of the list)
    private void moveSelection(ChatModel chat, int delta) {
        if (chat == null) return;
        List<MessageModel> msgs = messageService.getMessages(chat.id());
        if (msgs.isEmpty()) return;
        if (selectedMsgIndex < 0) {
            selectedMsgIndex = msgs.size() - 1;
            if (delta < 0) return;
        }
        selectedMsgIndex = Math.max(0, Math.min(msgs.size() - 1, selectedMsgIndex + delta));
    }

    // shows a transient status message in the input hint area (~3s)
    private void flashStatus(String msg) {
        actionStatus = msg;
        actionStatusAt = System.currentTimeMillis();
    }

    // only plain-text (and unknown-typed) messages can be edited via editMessageText
    private static boolean isEditable(MessageModel m) {
        return "messageText".equals(m.contentType()) || "unknown".equals(m.contentType());
    }

    public void render(TextGraphics g, Box box) {
        tryApplyPendingJump();
        tabBar.render(g, box);
        ChatModel chat = tabBar.activeChat();
        if (chat == null) {
            stickerPlacements.clear();
            ConversationRenderer.renderPlaceholder(g, box.getInnerLeft(), box.getInnerTop(),
                    box.getInnerWidth(), box.getInnerHeight());
            return;
        }

        int x = box.getInnerLeft();
        int y = box.getInnerTop();
        int w = box.getInnerWidth();
        int h = box.getInnerHeight();

        String hintText = buildInputHint();

        // input area grows with content up to 4 lines; hints always use just 1 line
        int inputWrapWidth = w - 3;
        List<String> inputLines = inputBuffer.getWrappedLines(inputWrapWidth);
        int inputHeight = (hintText != null) ? 1 : Math.min(4, inputLines.size());

        int separatorRow = y + h - inputHeight - 2;
        int inputTop = separatorRow + 2;
        int messagesTop = y;
        int messagesHeight = separatorRow - messagesTop;

        // yellow separator means there are newer messages below the current view
        List<MessageModel> msgsForSep = messageService.getMessages(chat.id());
        boolean hasNewerBelow = selectedMsgIndex >= 0 && selectedMsgIndex < msgsForSep.size() - 1;
        g.setBackgroundColor(CatppuccinMocha.BASE);
        g.setForegroundColor(hasNewerBelow ? CatppuccinMocha.YELLOW : CatppuccinMocha.SURFACE2);
        for (int col = x; col < x + w; col++) {
            g.putString(col, separatorRow, "─");
        }

        inputBuffer.renderReplyContext(g, x, separatorRow + 1, w);
        inputBuffer.renderInput(g, x, inputTop, w, inputLines, inputHeight, hintText);

        if (messageService.isLoading(chat.id())) {
            ConversationRenderer.renderLoading(g, x, messagesTop, w, messagesHeight);
            stickerPlacements.clear();
        } else {
            stickerPlacements.clear();
            viewportTop = ConversationRenderer.renderMessages(g, x, messagesTop, w, messagesHeight,
                    chat, selectedMsgIndex, viewportTop, messageService, inlineImages, stickerPlacements);
        }

        // url picker floats on top of everything else
        if (urlPickerActive && !urlPickerList.isEmpty()) {
            ConversationRenderer.renderUrlPicker(g, x, y, w, h, urlPickerList, urlPickerIndex);
        }
    }

    private void tryApplyPendingJump() {
        if (pendingJumpMessageId == 0 || tabBar.getActiveTabIndex() < 0) return;
        ChatModel chat = tabBar.activeChat();
        if (chat == null) return;
        List<MessageModel> msgs = messageService.getMessages(chat.id());
        for (int i = 0; i < msgs.size(); i++) {
            if (msgs.get(i).id() == pendingJumpMessageId) {
                selectedMsgIndex = i;
                viewportTop = Integer.MAX_VALUE / 2;
                pendingJumpMessageId = 0;
                return;
            }
        }
    }
}
