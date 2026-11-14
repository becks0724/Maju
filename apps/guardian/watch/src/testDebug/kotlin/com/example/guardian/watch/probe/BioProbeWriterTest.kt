// debug 전용 생체 기록 쓰기 스레드 시험 — 주기 flush, close 때 남은 줄과 순서, 닫힌 뒤 append 무시, 쓸 수 없는 경로의 실패 계수
package com.example.guardian.watch.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BioProbeWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun closeAndWait(w: BioProbeWriter) {
        val done = CountDownLatch(1)
        w.close { done.countDown() }
        assertTrue("close 가 끝나야 한다", done.await(5, TimeUnit.SECONDS))
    }

    @Test fun periodicFlushWritesToDiskBeforeClose() {
        val dir = File(tmp.root, "s1")
        val w = BioProbeWriter(dir, flushMs = 50)
        w.append(BioStream.EVENTS, "1,start,x")
        val f = File(dir, BioStream.EVENTS.file)
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline && (!f.exists() || !f.readText().contains("1,start,x"))) Thread.sleep(20)
        assertEquals(listOf(BioProbeCsv.EVENTS_HEADER, "1,start,x"), f.readLines())
        closeAndWait(w)
    }

    @Test fun closeWritesRemainingLinesInOrderWithHeaderOnce() {
        val dir = File(tmp.root, "s2")
        val w = BioProbeWriter(dir, flushMs = 60_000) // 주기 flush 가 끼어들지 않게 길게
        w.append(BioStream.SDK_HR, "a")
        w.append(BioStream.SDK_HR, "b")
        w.append(BioStream.META, "meta-1")
        w.append(BioStream.SDK_HR, "c")
        assertEquals(3L, w.count(BioStream.SDK_HR))
        closeAndWait(w)
        assertEquals(listOf(BioProbeCsv.SDK_HR_HEADER, "a", "b", "c"), File(dir, BioStream.SDK_HR.file).readLines())
        assertEquals(listOf("meta-1"), File(dir, BioStream.META.file).readLines()) // meta.txt 는 머리글 없음
        assertEquals(0L, w.ioErrorCount())
    }

    @Test fun appendAfterCloseIsIgnored() {
        val dir = File(tmp.root, "s3")
        val w = BioProbeWriter(dir, flushMs = 60_000)
        w.append(BioStream.SPEED, "1,0,,,")
        closeAndWait(w)
        w.append(BioStream.SPEED, "2,0,,,")
        w.append(BioStream.AND_ACC, "late")
        Thread.sleep(100)
        assertEquals(1L, w.count(BioStream.SPEED))
        assertEquals(0L, w.count(BioStream.AND_ACC))
        assertEquals(listOf(BioProbeCsv.SPEED_HEADER, "1,0,,,"), File(dir, BioStream.SPEED.file).readLines())
        assertTrue(!File(dir, BioStream.AND_ACC.file).exists())
    }

    @Test fun unwritablePathCountsIoErrorsPerLine() {
        val blocker = tmp.newFile("not-a-dir") // 파일 아래에 폴더를 만들 수 없다
        val w = BioProbeWriter(File(blocker, "sub"), flushMs = 60_000)
        w.append(BioStream.EVENTS, "1,a,b")
        w.append(BioStream.EVENTS, "2,a,b")
        w.append(BioStream.STATE, "3")
        closeAndWait(w)
        assertEquals(3L, w.ioErrorCount())
        assertEquals(2L, w.count(BioStream.EVENTS)) // 건수는 넘긴 줄 수, 실패는 따로 센다
    }

    @Test fun countedSnapshotFollowsCountedOrder() {
        val w = BioProbeWriter(File(tmp.root, "s5"), flushMs = 60_000)
        w.append(BioStream.AND_GYRO, "g")
        w.append(BioStream.SPEED, "s")
        w.append(BioStream.SPEED, "s")
        val snap = w.countedSnapshot()
        assertEquals(BioStream.COUNTED.size, snap.size)
        assertEquals(1L, snap[BioStream.COUNTED.indexOf(BioStream.AND_GYRO)])
        assertEquals(2L, snap[BioStream.COUNTED.indexOf(BioStream.SPEED)])
        closeAndWait(w)
    }
}
