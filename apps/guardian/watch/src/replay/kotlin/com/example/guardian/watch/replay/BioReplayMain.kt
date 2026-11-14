// Mac 쪽 명령 진입점 — `./gradlew :watch:replayBioSession -Psession=<세션 폴더>` 가 부른다. 세션 폴더를 읽어 제품 관문에 재생하고 replay/ 에 결과를 쓴다
package com.example.guardian.watch.replay

import java.io.File
import kotlin.system.exitProcess

object BioReplayMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val path = args.firstOrNull()?.takeIf { it.isNotBlank() }
        if (path == null) {
            System.err.println("사용법 — ./gradlew :watch:replayBioSession -Psession=<세션 폴더(yyyyMMdd-HHmmss)>")
            exitProcess(2)
        }
        val dir = File(path).absoluteFile
        if (!dir.isDirectory) {
            System.err.println("세션 폴더가 아닙니다 — $dir")
            exitProcess(2)
        }
        val out = run(dir)
        println("재생 끝 — ${File(out, SummaryWriter.SUMMARY_MD)} · ${File(out, SummaryWriter.VERDICT_CSV)}")
    }

    /** 폴더 하나를 재생하고 쓴 `replay/` 폴더를 돌려준다(시험에서도 부른다). */
    fun run(dir: File): File {
        val session = BioSession.load(dir)
        val report = ReplayReport.build(session)
        return SummaryWriter.write(report)
    }
}
