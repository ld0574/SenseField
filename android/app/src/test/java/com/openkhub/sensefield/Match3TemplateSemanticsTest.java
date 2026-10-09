package com.openkhub.sensefield;

import static org.junit.Assert.*;
import org.junit.Test;

/** A learned appearance/name alone cannot establish an animal's color or swap rules. */
public final class Match3TemplateSemanticsTest {
    @Test public void unknownSpecialMechanicsCannotInventAnOrdinaryMatchOrExchange() {
        for (char code : new char[]{'1', '9', 'a', 'z'}) {
            assertFalse(Match3Sampler.isMovable(code));
            assertFalse("A recognized template label is still readable", Match3Sampler.isUnknown(code));
            assertTrue(Match3Board.findRuns(new char[][]{{code, code, code}}).isEmpty());
            char[][] b = {{'R', 'G', code}, {'Y', 'O', code}, {'B', code, 'G'}};
            assertTrue(Match3Board.findSwaps(b).isEmpty());
        }
    }

    @Test public void aColorWordInsideACustomNameCannotChangeItsGameRules() {
        for (String name : new String[]{"红色木箱", "蓝色冰块", "黄色障碍", "紫色炸弹", "绿色藤蔓", "熊猫障碍"})
            assertEquals(name, Match3Sampler.UNKNOWN, Match3Sampler.nameToLetter(name));
        assertEquals('R', Match3Sampler.nameToLetter("红狐狸"));
        assertEquals('R', Match3Sampler.nameToLetter("狐狸红"));
        assertEquals('O', Match3Sampler.nameToLetter("棕熊棕"));
    }
}
