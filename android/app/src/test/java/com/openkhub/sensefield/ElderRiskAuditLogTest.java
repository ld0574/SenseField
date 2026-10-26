package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * ElderRiskAuditLog 测试：写文件、追加、空行跳过、文件大小增长、close 后不能再写。
 *
 * 注：此类依赖 File I/O，必须在 JVM 单元测试里跑；instrumented test 需要设备。
 */
public class ElderRiskAuditLogTest {

    @Test
    public void openWritesLinesToFile() throws IOException {
        File tmp = Files.createTempFile("elder-audit-", ".jsonl").toFile();
        tmp.deleteOnExit();
        ElderRiskAuditLog log = ElderRiskAuditLog.openAt(tmp);
        try {
            assertEquals(0, log.fileSize());
            log.appendLines(Collections.singletonList("{\"tier\":\"REVIEW\"}"));
            assertTrue(log.fileSize() > 0);
            String content = new String(Files.readAllBytes(tmp.toPath()));
            assertTrue(content.contains("\"tier\":\"REVIEW\""));
        } finally {
            log.close();
        }
    }

    @Test
    public void multipleAppendsGrowTheFile() throws IOException {
        File tmp = Files.createTempFile("elder-audit-", ".jsonl").toFile();
        tmp.deleteOnExit();
        ElderRiskAuditLog log = ElderRiskAuditLog.openAt(tmp);
        try {
            log.appendLines(Arrays.asList("{\"tier\":\"REVIEW\"}", "{\"tier\":\"GREEN\"}"));
            long size1 = log.fileSize();
            log.appendLines(Collections.singletonList("{\"tier\":\"NEUTRAL\"}"));
            assertTrue("追加后文件变大", log.fileSize() > size1);
        } finally {
            log.close();
        }
    }

    @Test
    public void emptyAndNullLinesAreSkippedQuietly() throws IOException {
        File tmp = Files.createTempFile("elder-audit-", ".jsonl").toFile();
        tmp.deleteOnExit();
        ElderRiskAuditLog log = ElderRiskAuditLog.openAt(tmp);
        try {
            log.appendLines(Arrays.asList("", null, "   "));
            assertEquals(0, log.fileSize());
        } finally {
            log.close();
        }
    }

    @Test
    public void appendWithNullListIsNoOp() throws IOException {
        File tmp = Files.createTempFile("elder-audit-", ".jsonl").toFile();
        tmp.deleteOnExit();
        ElderRiskAuditLog log = ElderRiskAuditLog.openAt(tmp);
        try {
            log.appendLines(null);
            assertEquals(0, log.fileSize());
        } finally {
            log.close();
        }
    }

    @Test
    public void closeThenAppendIsSilentNotCrash() throws IOException {
        File tmp = Files.createTempFile("elder-audit-", ".jsonl").toFile();
        tmp.deleteOnExit();
        ElderRiskAuditLog log = ElderRiskAuditLog.openAt(tmp);
        log.close();
        // close 后再 append 不应抛异常
        log.appendLines(Collections.singletonList("{\"tier\":\"REVIEW\"}"));
        // 文件内容不受影响（close 后 writer 已关）
        assertEquals(0, log.fileSize());
    }
}
