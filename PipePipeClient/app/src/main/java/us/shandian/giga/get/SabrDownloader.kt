package us.shandian.giga.get

import org.schabi.newpipe.extractor.services.youtube.sabr.exception.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormatTimeline
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrRequest
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession
import org.schabi.newpipe.youtube.SabrAttestationRetryHandler
import org.schabi.newpipe.youtube.SabrPlayerReloader
import org.schabi.newpipe.youtube.SabrRequestCoordinator
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.channels.ClosedByInterruptException
import java.io.RandomAccessFile
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.function.BooleanSupplier

internal class SabrDownloader(
    private val mission: DownloadMission,
) : Runnable {
    override fun run() {
        try {
            ensureRunning()
            val recoveries = validateRecoveryInfo()
            val info = SabrDownloadFormatResolver.resolveInfo(recoveries)

            val expectedLength = recoveries.map { recovery ->
                when (recovery.kind) {
                    'a' -> SabrDownloadFormatResolver.selectedAudioFormat(info, arrayOf(recovery))
                    'v' -> SabrDownloadFormatResolver.selectedVideoFormat(info, arrayOf(recovery))
                    else -> null
                }
            }.filterNotNull()
                .takeIf { formats -> formats.size == recoveries.size && formats.all { it.contentLength > 0 } }
                ?.sumOf { it.contentLength }
                ?: 0L
            prepareMission(expectedLength)
            val attestationRetryHandler = SabrAttestationRetryHandler(info.videoId, info.poToken)
            runSessionAttempt(info, recoveries, attestationRetryHandler)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: SocketTimeoutException) {
            // Timeouts are retried to exhaustion in requestWithNetworkRetry;
            // a timeout surfacing here is a genuine failure, not a pause.
            notifyErrorIfRunning(error)
        } catch (error: InterruptedIOException) {
            Thread.currentThread().interrupt()
        } catch (error: ClosedByInterruptException) {
            // NIO interrupt: same pause semantics as InterruptedIOException.
            Thread.currentThread().interrupt()
        } catch (error: SabrProtocolException) {
            notifyErrorIfRunning(classifyProtocolException(error))
        } catch (error: ExtractionException) {
            // Reload/integrity failures from the session are protocol
            // errors, not unknown bugs.
            notifyErrorIfRunning(
                SabrDownloadException(
                    SabrDownloadException.Reason.PROTOCOL,
                    "SABR download failed: request failed (${error.message.orEmpty()})",
                    error,
                )
            )
        } catch (error: Exception) {
            notifyErrorIfRunning(error)
        }
    }

    @Throws(IOException::class, InterruptedException::class, SabrProtocolException::class)
    private fun runSessionAttempt(
        info: YoutubeSabrInfo,
        recoveries: Array<MissionRecoveryInfo>,
        attestationRetryHandler: SabrAttestationRetryHandler,
    ) {
        val session = YoutubeSabrSession(info)
        val poToken = info.poToken
            ?: throw SabrProtocolException("SABR info has no player PO token")
        session.setPoToken(poToken)
        session.setInfoReloader(SabrPlayerReloader)
        val workDir = prepareWorkDirectory()
        val targets = SabrDownloadFormatResolver.buildTargets(info, recoveries, workDir)
        // SabrSegmentWriter routes segments by (itag, xtags) to the first
        // matching target; duplicates would starve the rest into STALLED.
        val duplicates = targets.groupBy { it.format.itag to it.format.xtags }
            .filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw SabrDownloadException(
                SabrDownloadException.Reason.FORMAT,
                "SABR download failed: duplicate formats $duplicates",
            )
        }
        // YoutubeSabrRequest accepts at most one audio and one video track;
        // same-kind extras would throw IllegalArgumentException downstream
        // instead of a clean FORMAT error.
        if (targets.count { it.format.isAudio } > 1 || targets.count { it.format.isVideo } > 1) {
            throw SabrDownloadException(
                SabrDownloadException.Reason.FORMAT,
                "SABR download failed: duplicate audio/video targets " +
                    "(mimes=${targets.map { it.format.mimeType }})",
            )
        }
        // YoutubeSabrRequest also rejects an audio and a video track
        // sharing one itag; fail closed with FORMAT before playback().
        val audioItags = targets.filter { it.format.isAudio }.map { it.format.itag }.toSet()
        val videoItags = targets.filter { it.format.isVideo }.map { it.format.itag }.toSet()
        val sharedItags = audioItags intersect videoItags
        if (sharedItags.isNotEmpty()) {
            throw SabrDownloadException(
                SabrDownloadException.Reason.FORMAT,
                "SABR download failed: audio/video share itag $sharedItags",
            )
        }
        restoreTargets(targets)
        val outputs = mutableMapOf<Int, FileOutputStream>()
        try {
            targets.forEach { target ->
                outputs[target.resourceIndex] = FileOutputStream(target.file, true)
            }
        } catch (error: IOException) {
            outputs.values.forEach { output ->
                try {
                    output.close()
                } catch (ignored: IOException) {
                }
            }
            throw storageException("could not open temporary media", error)
        }

        try {
            val requestCoordinator = SabrRequestCoordinator(
                session,
                attestationRetryHandler,
                null,
            )
            downloadSegments(
                requestCoordinator,
                targets,
                SabrSegmentWriter(targets, outputs, ::reportBytesWritten),
            )
        } finally {
            outputs.values.forEach { output ->
                try {
                    output.close()
                } catch (ignored: Exception) {
                    // Nothing to do.
                }
            }
        }

        ensureRunning()
        val finalBytes = SabrJavaMuxer(mission).remuxAndCopy(
            targets.map { it.file },
            targets,
            workDir,
        )
        completeMission(finalBytes)
    }

    @Throws(IOException::class)
    private fun validateRecoveryInfo(): Array<MissionRecoveryInfo> {
        val recoveries = mission.recoveryInfo ?: throw SabrDownloadException(
            SabrDownloadException.Reason.FORMAT,
            "SABR download failed: missing recovery info",
        )
        if (recoveries.size != mission.urls.size) {
            throw SabrDownloadException(
                SabrDownloadException.Reason.FORMAT,
                "SABR download failed: recovery count ${recoveries.size} != url count ${mission.urls.size}",
            )
        }
        // Elements are nullable (Java array); a null entry is corrupt
        // recovery info, not a reason to NPE into UNKNOWN.
        if (recoveries.any { it == null || !it.isSabr }) {
            throw SabrDownloadException(
                SabrDownloadException.Reason.FORMAT,
                "SABR download failed: mixed SABR/non-SABR resources are not supported",
            )
        }
        return recoveries
    }

    private fun prepareMission(expectedLength: Long) {
        if (mission.sabrCheckpoint?.version != SabrDownloadCheckpoint.VERSION) {
            mission.sabrCheckpoint = null
            cleanup(mission)
        }
        mission.done = restoredProgress()
        mission.nearLength = expectedLength.coerceAtLeast(mission.nearLength)
        mission.unknownLength = mission.nearLength <= 0
        mission.sabrStarted = true
        if (mission.nearLength > 0) {
            mission.length = mission.length
                .coerceAtLeast(mission.nearLength)
                .coerceAtLeast(mission.done)
        }
        mission.current = 0
        mission.writeThisToFile()
    }

    private fun reportBytesWritten(target: SabrDownloadTarget, delta: Long) {
        if (delta <= 0) {
            return
        }
        updateCheckpoint(target)
        if (mission.nearLength > 0) {
            mission.length = mission.length
                .coerceAtLeast(mission.nearLength)
                .coerceAtLeast(mission.done + delta)
            mission.unknownLength = false
        }
        mission.notifyProgress(delta)
    }

    @Throws(IOException::class)
    private fun prepareWorkDirectory(): File {
        val workDir = workDirectory(mission)
        // mkdirs() returns false both when creation failed and when a
        // concurrent creator won the race; re-check before failing.
        if (!workDir.exists() && !workDir.mkdirs() && !workDir.exists()) {
            throw storageException("could not create temporary directory", null)
        }
        return workDir
    }

    private fun restoreTargets(targets: List<SabrDownloadTarget>) {
        try {
            val kept = mutableListOf<SabrResourceCheckpoint>()
            targets.forEach { target ->
                val checkpoint = mission.sabrCheckpoint?.resources?.firstOrNull {
                    it.resourceIndex == target.resourceIndex &&
                        it.itag == target.format.itag &&
                        it.xtags == target.format.xtags &&
                        it.tempFilePath == target.file.absolutePath &&
                        it.nextWriteSequence > 0 &&
                        it.bytesWritten >= it.initializationBytes &&
                        it.initializationBytes >= 0 &&
                        it.initializationBytes <= MAX_INITIALIZATION_BYTES &&
                        target.file.exists() &&
                        target.file.length() >= it.bytesWritten
                }
                if (checkpoint == null) {
                    // File name only: the temp-dir absolute path would leak
                    // device layout into the user-visible storage message.
                    if (target.file.exists() && !target.file.delete()) {
                        throw IOException("Could not reset ${target.file.name}")
                    }
                    return@forEach
                }
                RandomAccessFile(target.file, "rw").use { file ->
                    // Re-check after open: a concurrent delete recreates the
                    // file via "rw" and must not be zero-padded as restored.
                    if (file.length() < checkpoint.bytesWritten) {
                        throw IOException("Temporary media changed during restore: ${target.file}")
                    }
                    file.setLength(checkpoint.bytesWritten)
                    if (checkpoint.initializationBytes > 0) {
                        val initialization = ByteArray(checkpoint.initializationBytes)
                        file.seek(0)
                        file.readFully(initialization)
                        target.initializationData = initialization
                        target.initializationWritten = true
                    }
                }
                target.nextWriteSequence = checkpoint.nextWriteSequence
                kept += checkpoint
            }
            // Drop stale resources (format/path change) so persisted progress
            // matches the files on disk instead of overcounting `done`.
            val current = mission.sabrCheckpoint
            if (current != null) {
                mission.sabrCheckpoint = current.copy(resources = kept.sortedBy { it.resourceIndex })
            }
            mission.done = targets.sumOf { if (it.file.exists()) it.file.length() else 0 }
            mission.writeThisToFile()
        } catch (error: IOException) {
            throw storageException("could not restore temporary media", error)
        }
    }

    private fun storageException(message: String, cause: IOException?): SabrDownloadException {
        // Include the cause message: ENOSPC/permission detail is the
        // diagnosable part of a storage failure.
        val detail = cause?.message.orEmpty()
        val suffix = if (detail.isBlank()) "" else " ($detail)"
        return SabrDownloadException(
            SabrDownloadException.Reason.STORAGE,
            "SABR download failed: $message$suffix",
            cause,
        )
    }

    private fun updateCheckpoint(target: SabrDownloadTarget) {
        val current = mission.sabrCheckpoint ?: SabrDownloadCheckpoint()
        val resources = current.resources
            .filterNot { it.resourceIndex == target.resourceIndex }
            .toMutableList()
        // Key the frozen size on format identity: a re-resolved itag/xtags
        // pair must not inherit the previous init size.
        val previousInitializationBytes = current.resources
            .firstOrNull {
                it.resourceIndex == target.resourceIndex &&
                    it.itag == target.format.itag &&
                    it.xtags == target.format.xtags
            }
            ?.initializationBytes
            ?: 0
        resources += SabrResourceCheckpoint(
            resourceIndex = target.resourceIndex,
            itag = target.format.itag,
            xtags = target.format.xtags,
            tempFilePath = target.file.absolutePath,
            nextWriteSequence = target.nextWriteSequence,
            bytesWritten = target.file.length(),
            initializationBytes = if (target.initializationWritten && previousInitializationBytes == 0) {
                target.initializationData?.size ?: 0
            } else {
                previousInitializationBytes
            },
        )
        mission.sabrCheckpoint = current.copy(resources = resources.sortedBy { it.resourceIndex })
        // HLS parity: persist immediately instead of relying on throttled
        // progress writes; a stale checkpoint only costs re-downloaded tail.
        mission.writeThisToFile()
    }

    private fun restoredProgress(): Long {
        return mission.sabrCheckpoint?.resources
            ?.filter { checkpoint ->
                File(checkpoint.tempFilePath).let { it.exists() && it.length() >= checkpoint.bytesWritten }
            }
            ?.sumOf { it.bytesWritten }
            ?: 0L
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun downloadSegments(
        requestCoordinator: SabrRequestCoordinator,
        targets: List<SabrDownloadTarget>,
        writer: SabrSegmentWriter,
    ) {
        writer.observeWrittenInitializations()
        prepareInitializations(requestCoordinator, targets, writer)
        // No second call: the loop-top observe below runs immediately and
        // covers both restored and freshly prepared inits.

        var noProgressResponses = 0
        while (true) {
            ensureRunning()
            writer.observeWrittenInitializations()
            if (isDownloadComplete(targets)) {
                break
            }

            val playerTimeMs = downloadPlayerTimeMs(targets)
            // One request track per download target: first-only selection
            // would starve same-kind extras (e.g. two audio tracks) into a
            // guaranteed STALLED loop.
            val tracks = targets.map { target ->
                YoutubeSabrRequest.Track.of(
                    target.format,
                    requireTimeline(target),
                    target.nextWriteSequence - 1,
                )
            }
            val sequencesBeforeRequest = targets.map { it.nextWriteSequence }
            requestWithNetworkRetry(
                requestCoordinator,
                YoutubeSabrRequest.playback(playerTimeMs, 1.0f, tracks),
                writer,
            )
            writer.observeWrittenInitializations()
            if (isDownloadComplete(targets)) {
                break
            }
            val madeProgress = targets.indices.any { index ->
                targets[index].nextWriteSequence > sequencesBeforeRequest[index]
            }
            if (madeProgress) {
                noProgressResponses = 0
            } else {
                noProgressResponses++
                if (noProgressResponses >= MAX_NO_PROGRESS_RESPONSES) {
                    throw SabrDownloadException(
                        SabrDownloadException.Reason.STALLED,
                        "SABR download stalled: no target sequence advanced after " +
                            "$MAX_NO_PROGRESS_RESPONSES responses",
                    )
                }
                Thread.sleep(IDLE_POLL_MS)
            }
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun prepareInitializations(
        requestCoordinator: SabrRequestCoordinator,
        targets: List<SabrDownloadTarget>,
        writer: SabrSegmentWriter,
    ) {
        val pendingTargets = targets.filterNot { it.initializationWritten }
        if (pendingTargets.isEmpty()) {
            return
        }

        ensureRunning()
        // Request only still-pending formats: restored inits must not be
        // re-fetched (returned media would bloat pending toward STALLED).
        requestWithNetworkRetry(
            requestCoordinator,
            YoutubeSabrRequest.preparation(0L, pendingTargets.map { it.format }),
            writer,
            { pendingTargets.all { it.initializationWritten } },
        )
        writer.observeWrittenInitializations()
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun requestWithNetworkRetry(
        requestCoordinator: SabrRequestCoordinator,
        request: YoutubeSabrRequest,
        writer: SabrSegmentWriter,
        progressChecker: BooleanSupplier? = null,
    ) {
        var attempts = 0
        while (true) {
            try {
                if (progressChecker != null) {
                    requestCoordinator.request(
                        request,
                        { segment -> writer.acceptSegment(segment) },
                        progressChecker,
                    )
                } else {
                    requestCoordinator.request(request) { segment ->
                        writer.acceptSegment(segment)
                    }
                }
                return
            } catch (error: IOException) {
                if (!isRetryableNetworkError(error) || attempts >= MAX_REQUEST_NETWORK_RETRIES) {
                    throw error
                }
                attempts++
                Thread.sleep(REQUEST_NETWORK_RETRY_DELAY_MS)
                ensureRunning()
            }
        }
    }

    private fun isRetryableNetworkError(error: IOException): Boolean {
        return error is SocketTimeoutException ||
            error is ConnectException ||
            error is UnknownHostException ||
            error is SocketException
    }

    private fun downloadPlayerTimeMs(targets: List<SabrDownloadTarget>): Long {
        return targets.minOf { target ->
            if (target.nextWriteSequence <= 1) {
                0L
            } else {
                // Fail fast on corrupt state (missing/negative timeline end)
                // instead of coercing -1 to 0 and re-requesting playback at
                // 0 until the stall timeout.
                val endMs = requireTimeline(target).getEndMs(target.nextWriteSequence - 1)
                if (endMs < 0) {
                    throw SabrDownloadException(
                        SabrDownloadException.Reason.FORMAT,
                        "SABR download failed: missing timeline entry for itag " +
                            "${target.format.itag} sequence ${target.nextWriteSequence - 1}",
                    )
                }
                endMs
            }
        }
    }

    private fun isDownloadComplete(targets: List<SabrDownloadTarget>): Boolean {
        return targets.all { target ->
            // A missing timeline after initialization can never complete;
            // fail fast instead of burning idle polls until STALLED.
            val endSequence = requireTimeline(target).endSequence
            target.pending.isEmpty() && target.nextWriteSequence > endSequence
        }
    }

    private fun requireTimeline(target: SabrDownloadTarget): YoutubeSabrFormatTimeline {
        return target.timeline ?: throw SabrDownloadException(
            SabrDownloadException.Reason.FORMAT,
            "SABR download failed: missing timeline for itag ${target.format.itag}",
        )
    }

    private fun completeMission(finalBytes: Long) {
        if (finalBytes <= 0) {
            mission.psState = 0
            mission.writeThisToFile()
            throw SabrDownloadException(
                SabrDownloadException.Reason.MUXING,
                "SABR download failed: empty final file",
            )
        }
        mission.done = finalBytes
        mission.length = finalBytes
        // Clear the itag-estimate ceiling: getLength() returns
        // max(calculated, nearLength), which would otherwise keep
        // reporting the over-estimate on a finished mission.
        mission.nearLength = finalBytes
        mission.current = mission.urls.size
        mission.psState = 2
        mission.sabrCheckpoint = null
        mission.sabrStarted = false
        cleanup(mission)
        mission.unknownLength = false
        mission.notifyFinished()
    }

    @Throws(InterruptedException::class)
    private fun ensureRunning() {
        if (!mission.running || Thread.currentThread().isInterrupted) {
            throw InterruptedException()
        }
    }

    // Retention is deliberate for resume: temp files are kept so a retry
    // can restore progress instead of redownloading. Named for what it
    // does (no cleanup happens here) so a future reader does not "fix"
    // it by deleting resume state.
    private fun notifyErrorIfRunning(error: Exception) {
        if (mission.running) {
            mission.notifyError(error)
        }
    }

    private fun classifyProtocolException(error: SabrProtocolException): SabrDownloadException {
        val message = error.message.orEmpty()
        val reason = when {
            message.contains("protected", ignoreCase = true) ||
                message.contains("PO token", ignoreCase = true) -> {
                SabrDownloadException.Reason.PROTECTED
            }
            message.contains("policy-only", ignoreCase = true) ||
                message.contains("not returned", ignoreCase = true) ||
                message.contains("integrity", ignoreCase = true) -> {
                SabrDownloadException.Reason.STALLED
            }
            else -> SabrDownloadException.Reason.PROTOCOL
        }
        return SabrDownloadException(
            reason,
            "SABR download failed: ${message.ifBlank { "protocol error" }}",
            error,
        )
    }

    companion object {
        // 250ms x 60 responses = 15s of no-advance before STALLED.
        private const val IDLE_POLL_MS = 250L
        private const val MAX_NO_PROGRESS_RESPONSES = 60
        private const val MAX_REQUEST_NETWORK_RETRIES = 2
        private const val REQUEST_NETWORK_RETRY_DELAY_MS = 1_000L
        // YouTube SABR inits are KiBs; 16 MiB bounds corrupt/extreme inits.
        private const val MAX_INITIALIZATION_BYTES = 16 * 1024 * 1024

        @JvmStatic
        fun cleanup(mission: DownloadMission) {
            try {
                workDirectory(mission).deleteRecursively()
            } catch (ignored: Exception) {
                // Nothing to do.
            }
        }

        private fun workDirectory(mission: DownloadMission): File {
            val base = mission.context.getExternalFilesDir(null) ?: mission.context.filesDir
            val missionId = if (mission.timestamp > 0) {
                mission.timestamp.toString()
            } else {
                // Salt with the url list so same-named missions never share
                // a work dir and clobber each other's input-*.media.
                val nameHash = mission.storage.name?.hashCode() ?: 0
                val urlsHash = mission.urls?.contentHashCode() ?: 0
                "${nameHash}_${urlsHash}"
            }
            return File(base, "sabr-downloader/$missionId")
        }
    }

}
