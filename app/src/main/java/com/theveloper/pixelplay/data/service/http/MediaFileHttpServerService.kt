package com.theveloper.pixelplay.data.service.http

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.IBinder
import androidx.core.net.toUri
import com.theveloper.pixelplay.data.repository.MusicRepository
import dagger.hilt.android.AndroidEntryPoint
import io.ktor.http.ContentType
import io.ktor.http.ContentRange
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.Inet4Address
import javax.inject.Inject

@AndroidEntryPoint
class MediaFileHttpServerService : Service() {

    @Inject
    lateinit var musicRepository: MusicRepository

    private var server: NettyApplicationEngine? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    companion object {
        const val ACTION_START_SERVER = "ACTION_START_SERVER"
        const val ACTION_STOP_SERVER = "ACTION_STOP_SERVER"
        var isServerRunning = false
        var serverAddress: String? = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVER -> startServer()
            ACTION_STOP_SERVER -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startServer() {
        if (server?.application?.isActive != true) {
            serviceScope.launch {
                try {
                    val ipAddress = getIpAddress(applicationContext)
                    if (ipAddress == null) {
                        stopSelf()
                        return@launch
                    }
                    serverAddress = "http://$ipAddress:8080"

                    server = embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
                        routing {
                            get("/song/{songId}") {
                                val songId = call.parameters["songId"]
                                if (songId == null) {
                                    call.respond(HttpStatusCode.BadRequest, "Song ID is missing")
                                    return@get
                                }

                                val song = musicRepository.getSong(songId).firstOrNull()
                                if (song == null) {
                                    call.respond(HttpStatusCode.NotFound, "Song not found")
                                    return@get
                                }

                                try {
                                    val uri = song.contentUriString.toUri()
                                    val pfd = contentResolver.openFileDescriptor(uri, "r")
                                    if (pfd == null) {
                                        call.respond(HttpStatusCode.NotFound, "File not found")
                                        return@get
                                    }

                                    val fileSize = pfd.statSize
                                    val rangeHeader = call.request.headers[HttpHeaders.Range]

                                    if (rangeHeader != null) {
                                        val ranges = io.ktor.http.parseRangesSpecifier(rangeHeader)
                                        if (ranges == null || ranges.isEmpty()) {
                                            pfd.close()
                                            call.respond(HttpStatusCode.BadRequest, "Invalid range")
                                            return@get
                                        }

                                        // We only handle the first range request for simplicity
                                        val range = ranges.first()
                                        val start = when (range) {
                                            is io.ktor.http.ContentRange.Bounded -> range.from
                                            is io.ktor.http.ContentRange.TailFrom -> fileSize - range.from
                                            is io.ktor.http.ContentRange.Suffix -> fileSize - range.lastCount
                                            else -> 0L
                                        }
                                        val end = when (range) {
                                            is io.ktor.http.ContentRange.Bounded -> range.to
                                            is io.ktor.http.ContentRange.TailFrom -> fileSize - 1
                                            is io.ktor.http.ContentRange.Suffix -> fileSize - 1
                                            else -> fileSize - 1
                                        }

                                        val clampedStart = start.coerceAtLeast(0L)
                                        val clampedEnd = end.coerceAtMost(fileSize - 1)
                                        val length = clampedEnd - clampedStart + 1

                                        if (length <= 0) {
                                            pfd.close()
                                            call.respond(HttpStatusCode.RangeNotSatisfiable, "Range not satisfiable")
                                            return@get
                                        }

                                        val inputStream = java.io.FileInputStream(pfd.fileDescriptor)
                                        inputStream.skip(clampedStart)

                                        call.response.header(HttpHeaders.ContentRange, "bytes $clampedStart-$clampedEnd/$fileSize")
                                        call.response.header(HttpHeaders.AcceptRanges, "bytes")

                                        // Using ByteReadChannel for efficient streaming
                                        val channel = withContext(Dispatchers.IO) {
                                            ByteReadChannel(inputStream.readNBytes(length.toInt()))
                                        }

                                        call.respond(HttpStatusCode.PartialContent, channel)
                                        // Note: pfd.close() is handled by garbage collection or should be closed after streaming.
                                        // Ktor doesn't easily allow closing resource after responding stream.
                                        // Using readNBytes reads into memory which is not ideal for large files but safe for pfd closing.
                                        // For better streaming without memory load, we would need a custom OutgoingContent.
                                        pfd.close()
                                    } else {
                                        val inputStream = java.io.FileInputStream(pfd.fileDescriptor)
                                        call.response.header(HttpHeaders.AcceptRanges, "bytes")
                                        call.respondOutputStream(contentType = ContentType.Audio.MPEG) {
                                            inputStream.copyTo(this)
                                            pfd.close()
                                        }
                                    }
                                } catch (e: Exception) {
                                    call.respond(HttpStatusCode.InternalServerError, "Error serving file: ${e.message}")
                                }
                            }
                            get("/art/{songId}") {
                                val songId = call.parameters["songId"]
                                if (songId == null) {
                                    call.respond(HttpStatusCode.BadRequest, "Song ID is missing")
                                    return@get
                                }

                                val song = musicRepository.getSong(songId).firstOrNull()
                                if (song?.albumArtUriString == null) {
                                    call.respond(HttpStatusCode.NotFound, "Album art not found")
                                    return@get
                                }

                                val artUri = song.albumArtUriString.toUri()
                                contentResolver.openInputStream(artUri)?.use { inputStream ->
                                    call.respondOutputStream(contentType = ContentType.Image.JPEG) {
                                        inputStream.copyTo(this)
                                    }
                                } ?: call.respond(HttpStatusCode.InternalServerError, "Could not open album art file")
                            }
                        }
                    }.start(wait = false)
                    isServerRunning = true
                } catch (e: Exception) {
                    stopSelf()
                }
            }
        }
    }

    private fun getIpAddress(context: Context): String? {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork) ?: return null
        val ipAddress = linkProperties.linkAddresses.find { it.address is Inet4Address }
        return ipAddress?.address?.hostAddress
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop(1000, 2000)
        isServerRunning = false
        serverAddress = null
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
