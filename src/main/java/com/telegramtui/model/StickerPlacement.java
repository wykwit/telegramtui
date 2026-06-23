package com.telegramtui.model;

/**
 * A sticker image to be placed on screen via the Kitty graphics protocol.
 * Coordinates are absolute terminal cell coordinates (0-based, matching Lanterna).
 */
public record StickerPlacement(int col, int row, int rows, long fileId) {
}
