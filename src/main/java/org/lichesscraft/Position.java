package org.lichesscraft;

import java.util.Arrays;

/** Replays server-confirmed standard chess moves. Never suggests or validates moves. */
public final class Position {
    private final char[] squares = new char[64];
    private int plies;
    public Position(String fen, String moves) {
        Arrays.fill(squares, '.');
        if (fen == null || fen.equals("startpos")) fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
        String[] ranks = fen.split(" ")[0].split("/");
        if (ranks.length != 8) throw new IllegalArgumentException("Invalid FEN");
        for (int row = 0; row < 8; row++) {
            int file = 0;
            for (char c : ranks[row].toCharArray()) {
                if (c >= '1' && c <= '8') file += c - '0';
                else { if (file >= 8 || "prnbqkPRNBQK".indexOf(c) < 0) throw new IllegalArgumentException("Invalid FEN"); squares[(7-row)*8 + file++] = c; }
            }
            if (file != 8) throw new IllegalArgumentException("Invalid FEN");
        }
        if (moves != null && !moves.isBlank()) for (String move : moves.trim().split("\\s+")) apply(move);
    }
    public static int index(String square) {
        if (!square.matches("[a-h][1-8]")) throw new IllegalArgumentException("Invalid square");
        return (square.charAt(1)-'1')*8 + square.charAt(0)-'a';
    }
    public static String name(int index) { return ""+(char)('a'+index%8)+(char)('1'+index/8); }
    private void apply(String move) {
        if (!move.matches("[a-h][1-8][a-h][1-8][qrbn]?")) throw new IllegalArgumentException("Invalid move");
        int from = index(move.substring(0,2)), to = index(move.substring(2,4));
        char piece = squares[from];
        if (piece == '.') throw new IllegalArgumentException("Empty source");
        if (Character.toLowerCase(piece) == 'p' && from%8 != to%8 && squares[to] == '.') squares[to + (Character.isUpperCase(piece) ? -8 : 8)] = '.';
        if (Character.toLowerCase(piece) == 'k' && Math.abs(to%8-from%8) == 2) {
            int rookFrom = from/8*8+(to>from ? 7 : 0), rookTo = from/8*8+(to>from ? 5 : 3);
            squares[rookTo] = squares[rookFrom]; squares[rookFrom] = '.';
        }
        squares[from] = '.';
        squares[to] = move.length()==5 ? (Character.isUpperCase(piece) ? Character.toUpperCase(move.charAt(4)) : move.charAt(4)) : piece;
        plies++;
    }
    public char at(int index) { return squares[index]; }
    public boolean whiteTurn() { return plies%2==0; }
}
