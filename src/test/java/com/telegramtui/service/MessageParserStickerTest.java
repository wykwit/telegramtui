package com.telegramtui.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.telegramtui.model.MessageModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageParserStickerTest {

    private final MessageParser parser = new MessageParser();

    private JsonObject stickerMessage(long fileId, String emoji) {
        String json = """
                {
                    "id": 100,
                    "chat_id": 200,
                    "is_outgoing": false,
                    "date": 1700000000,
                    "content": {
                        "@type": "messageSticker",
                        "sticker": {
                            "emoji": "%s",
                            "thumbnail": {
                                "file": {
                                    "@type": "file",
                                    "id": %d
                                }
                            }
                        }
                    }
                }""".formatted(emoji, fileId);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void parseStickerWithThumbnail() {
        JsonObject msg = stickerMessage(789, "🐱");
        MessageModel m = parser.parseMessage(msg);

        assertNotNull(m);
        assertEquals(789, m.stickerFileId());
        assertEquals("🐱", m.stickerEmoji());
        assertEquals("messageSticker", m.contentType());
    }

    @Test
    void parseStickerWithoutThumbnail() {
        String json = """
                {
                    "id": 101,
                    "chat_id": 200,
                    "date": 1700000000,
                    "content": {
                        "@type": "messageSticker",
                        "sticker": {
                            "emoji": "🐶"
                        }
                    }
                }""";
        MessageModel m = parser.parseMessage(JsonParser.parseString(json).getAsJsonObject());

        assertNotNull(m);
        assertEquals(0, m.stickerFileId(), "stickerFileId should be 0 when no thumbnail");
        assertEquals("🐶", m.stickerEmoji());
    }

    @Test
    void parseStickerWithoutEmoji() {
        String json = """
                {
                    "id": 102,
                    "chat_id": 200,
                    "date": 1700000000,
                    "content": {
                        "@type": "messageSticker",
                        "sticker": {}
                    }
                }""";
        MessageModel m = parser.parseMessage(JsonParser.parseString(json).getAsJsonObject());

        assertNotNull(m);
        assertEquals(0, m.stickerFileId());
        assertEquals("", m.stickerEmoji());
    }

    @Test
    void parseNonStickerMessageHasNoStickerData() {
        String json = """
                {
                    "id": 103,
                    "chat_id": 200,
                    "date": 1700000000,
                    "content": {
                        "@type": "messageText",
                        "text": { "text": "hello" }
                    }
                }""";
        MessageModel m = parser.parseMessage(JsonParser.parseString(json).getAsJsonObject());

        assertNotNull(m);
        assertEquals(0, m.stickerFileId());
        assertEquals("", m.stickerEmoji());
        assertEquals("hello", m.text());
    }

    @Test
    void parseMalformedStickerDoesNotThrow() {
        String json = """
                {
                    "id": 104,
                    "chat_id": 200,
                    "date": 1700000000,
                    "content": {
                        "@type": "messageSticker",
                        "sticker": "not-an-object"
                    }
                }""";
        MessageModel m = assertDoesNotThrow(
                () -> parser.parseMessage(JsonParser.parseString(json).getAsJsonObject()));

        assertNotNull(m);
        assertEquals(0, m.stickerFileId());
    }

    @Test
    void parseStickerWithThumbnailButNoFileId() {
        String json = """
                {
                    "id": 105,
                    "chat_id": 200,
                    "date": 1700000000,
                    "content": {
                        "@type": "messageSticker",
                        "sticker": {
                            "emoji": "🦊",
                            "thumbnail": {
                                "file": {}
                            }
                        }
                    }
                }""";
        MessageModel m = parser.parseMessage(JsonParser.parseString(json).getAsJsonObject());

        assertNotNull(m);
        assertEquals(0, m.stickerFileId(), "stickerFileId should be 0 when file has no id");
        assertEquals("🦊", m.stickerEmoji());
    }
}
