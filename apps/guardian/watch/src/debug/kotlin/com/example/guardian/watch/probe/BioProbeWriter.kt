// debug 전용 생체 기록 — 전용 스레드 하나가 스트림별 파일에 줄을 쌓고 2초마다 디스크로 비운다(센서 콜백은 줄을 넘기기만 한다)
package com.example.guardian.watch.probe

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.EnumMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * - [append] 는 어느 스레드에서 불러도 된다. 줄은 쓰기 스레드 큐로 넘어가고, 파일 열기 · 쓰기는 그 스레드에서만 한다.
 * - 파일은 처음 줄이 올 때 열고 머리글을 먼저 쓴다. [FLUSH_MS] 마다 모두 flush 하므로 비정상 종료에도 그때까지의 기록이 남는다.
 * - 건수는 스트림별 [AtomicLong] — state.csv 와 화면이 읽는다. 쓰기 실패는 [ioErrors] 로 센다(값은 logcat 에 찍지 않는다, G2).
 */
class BioProbeWriter(private val dir: File, flushMs: Long = FLUSH_MS) {
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bio-probe-writer") }
    private val writers = EnumMap<BioStream, BufferedWriter>(BioStream::class.java)
    private val counts = BioStream.entries.associateWith { AtomicLong() }
    private val ioErrors = AtomicLong()

    init {
        exec.execute { dir.mkdirs() }
        exec.scheduleWithFixedDelay(::flushAll, flushMs, flushMs, TimeUnit.MILLISECONDS)
    }

    fun append(stream: BioStream, line: String) {
        try {
            exec.execute { write(stream, line) }
            counts.getValue(stream).incrementAndGet()
        } catch (_: RejectedExecutionException) {
            // 닫힌 뒤에 온 줄 — 버린다
        }
    }

    fun count(stream: BioStream): Long = counts.getValue(stream).get()

    /** [BioStream.COUNTED] 순서의 누적 건수(state.csv 열 순서). */
    fun countedSnapshot(): List<Long> = BioStream.COUNTED.map(::count)

    fun ioErrorCount(): Long = ioErrors.get()

    /** 남은 줄을 다 쓰고 파일을 닫는다. [onClosed] 는 쓰기 스레드에서 마지막에 부른다. */
    fun close(onClosed: () -> Unit) {
        try {
            exec.execute {
                flushAll()
                writers.values.forEach { w -> runCatching { w.close() } }
                writers.clear()
                onClosed()
            }
        } catch (_: RejectedExecutionException) {
            onClosed()
        }
        exec.shutdown()
    }

    private fun write(stream: BioStream, line: String) {
        try {
            val w = writers[stream] ?: open(stream).also { writers[stream] = it }
            w.write(line)
            w.newLine()
        } catch (_: IOException) {
            ioErrors.incrementAndGet()
        }
    }

    private fun open(stream: BioStream): BufferedWriter {
        val f = File(dir, stream.file)
        val fresh = !f.exists() || f.length() == 0L
        val w = BufferedWriter(FileWriter(f, true), BUFFER_BYTES)
        val header = BioProbeCsv.header(stream)
        if (fresh && header != null) {
            w.write(header)
            w.newLine()
        }
        return w
    }

    private fun flushAll() {
        writers.values.forEach { w ->
            try { w.flush() } catch (_: IOException) { ioErrors.incrementAndGet() }
        }
    }

    companion object {
        const val FLUSH_MS = 2_000L
        /** PPG 25Hz 세 파장 한 줄 ≈ 60바이트 → 2초에 약 3KB. 주기 flush 전에 버퍼가 스스로 비워지지 않게 넉넉히 잡는다. */
        private const val BUFFER_BYTES = 64 * 1024
    }
}
