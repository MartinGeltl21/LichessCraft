package org.lichesscraft;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PositionTest {
    @Test void startingPositionAndCoordinates() {
        Position p=new Position("startpos","");assertEquals('K',p.at(Position.index("e1")));assertEquals('k',p.at(Position.index("e8")));assertTrue(p.whiteTurn());
        for(int i=0;i<64;i++)assertEquals(i,Position.index(Position.name(i)));
    }
    @Test void capturesAndTurns() {
        Position p=new Position("startpos","e2e4 d7d5 e4d5");assertEquals('P',p.at(Position.index("d5")));assertEquals('.',p.at(Position.index("e4")));assertFalse(p.whiteTurn());
    }
    @Test void bothCastles() {
        Position p=new Position("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1","e1g1 e8c8");assertEquals('K',p.at(Position.index("g1")));assertEquals('R',p.at(Position.index("f1")));assertEquals('k',p.at(Position.index("c8")));assertEquals('r',p.at(Position.index("d8")));assertEquals('.',p.at(Position.index("h1")));
    }
    @Test void whiteEnPassant() {
        Position p=new Position("startpos","e2e4 a7a6 e4e5 d7d5 e5d6");assertEquals('P',p.at(Position.index("d6")));assertEquals('.',p.at(Position.index("d5")));
    }
    @Test void blackEnPassant() {
        Position p=new Position("startpos","a2a3 e7e5 a3a4 e5e4 d2d4 e4d3");assertEquals('p',p.at(Position.index("d3")));assertEquals('.',p.at(Position.index("d4")));
    }
    @Test void promotionsAndUnderpromotions() {
        for(char piece:"qrbn".toCharArray()) {
            Position p=new Position("7k/P7/8/8/8/8/7p/K7 w - - 0 1","a7a8"+piece+" h2h1"+piece);
            assertEquals(Character.toUpperCase(piece),p.at(Position.index("a8")));assertEquals(piece,p.at(Position.index("h1")));
        }
    }
    @Test void invalidDataFailsClosed() {
        assertThrows(IllegalArgumentException.class,()->Position.index("z9"));assertThrows(IllegalArgumentException.class,()->new Position("bad",""));assertThrows(IllegalArgumentException.class,()->new Position("startpos","e3e4"));assertThrows(IllegalArgumentException.class,()->new Position("startpos","e2e9"));
    }
}
