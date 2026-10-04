package com.openkhub.sensefield;

import org.junit.Test;
import static org.junit.Assert.*;

public class AsrUtteranceBufferTest {
    @Test public void capturedAudioIsCopiedAndCancelledAudioCannotReachNextTurn() {
        AsrUtteranceBuffer buffer = new AsrUtteranceBuffer();
        short[] input = {10, 20}; buffer.begin(); buffer.accept(input); input[0] = 99;
        assertArrayEquals(new short[]{10, 20}, buffer.finish());
        buffer.begin(); buffer.accept(new short[]{30}); buffer.clear();
        buffer.accept(new short[]{40}); assertNull(buffer.finish());
        buffer.begin(); buffer.accept(new short[]{50}); assertArrayEquals(new short[]{50}, buffer.finish());
        assertNull(buffer.finish());
    }
    @Test public void overlongUtteranceIsDiscardedRatherThanTruncatedOrQueued() {
        AsrUtteranceBuffer buffer = new AsrUtteranceBuffer();
        buffer.begin(); buffer.accept(new short[AsrUtteranceBuffer.MAX_SAMPLES]);
        buffer.accept(new short[]{1}); assertNull(buffer.finish());
        buffer.begin(); buffer.accept(new short[]{2}); assertArrayEquals(new short[]{2}, buffer.finish());
    }
}
