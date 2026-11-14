package app.nextdata.android

import android.media.MediaDataSource
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.nextdata.platform.MediaRequest
import app.nextdata.platform.RangeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Feeds [MediaPlayer] from the server in ranges (`read.*`): seeking does not download the whole file and nothing is
 * stored. Reads are aligned to [BLOCK] bytes and a few blocks are kept, so the player's small reads do not each cost
 * a network round trip. A failure surfaces as an I/O error to the player (the UI offers "open with another app").
 */
class RangeDataSource(private val src: RangeSource, private val total: Long?) : MediaDataSource() {
    private val cache = object : LinkedHashMap<Long, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > MAX_BLOCKS
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (total != null && position >= total) return -1
        val index = position / BLOCK
        val block = cache[index] ?: runBlocking { src.read(index * BLOCK, BLOCK) }.also { if (it.isNotEmpty()) cache[index] = it }
        val inBlock = (position - index * BLOCK).toInt()
        if (inBlock >= block.size) return -1
        val n = minOf(size, block.size - inBlock)
        System.arraycopy(block, inBlock, buffer, offset, n)
        return n
    }

    override fun getSize(): Long = total ?: -1L

    override fun close() {
        runBlocking { src.close() }
    }

    companion object {
        const val BLOCK = 256 * 1024
        const val MAX_BLOCKS = 8
    }
}

@Composable
fun RangeMediaPlayer(req: MediaRequest, modifier: Modifier = Modifier) {
    var failed by remember(req) { mutableStateOf(false) }
    var prepared by remember(req) { mutableStateOf(false) }
    var playing by remember(req) { mutableStateOf(false) }
    var position by remember(req) { mutableFloatStateOf(0f) }
    var duration by remember(req) { mutableFloatStateOf(1f) }
    var seeking by remember(req) { mutableStateOf(false) }
    val player = remember(req) { MediaPlayer() }
    val dataSource = remember(req) { RangeDataSource(req.source, req.size) }

    DisposableEffect(req) {
        player.setOnPreparedListener { p ->
            duration = p.duration.coerceAtLeast(1).toFloat()
            prepared = true
            p.start()
            playing = true
        }
        player.setOnCompletionListener { playing = false }
        player.setOnErrorListener { _, _, _ -> failed = true; true }
        runCatching {
            player.setDataSource(dataSource)
            player.prepareAsync()
        }.onFailure { failed = true }
        onDispose {
            runCatching { player.release() }
            runCatching { dataSource.close() }
        }
    }
    LaunchedEffect(req, prepared) {
        while (prepared) {
            if (!seeking) position = runCatching { player.currentPosition.toFloat() }.getOrDefault(position)
            delay(400)
        }
    }

    Column(modifier.fillMaxSize()) {
        if (req.video) {
            AndroidView(
                factory = { c ->
                    SurfaceView(c).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) { runCatching { player.setDisplay(h) } }
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) = Unit
                            override fun surfaceDestroyed(h: SurfaceHolder) { runCatching { player.setDisplay(null) } }
                        })
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            Text(req.name, Modifier.weight(1f).fillMaxWidth().padding(24.dp), style = MaterialTheme.typography.titleMedium)
        }
        if (failed) {
            Text(stringResource(R.string.media_error), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                TextButton(
                    onClick = {
                        if (!prepared) return@TextButton
                        if (playing) { player.pause(); playing = false } else { player.start(); playing = true }
                    },
                ) { Text(stringResource(if (playing) R.string.media_pause else R.string.media_play)) }
                Slider(
                    value = position.coerceIn(0f, duration),
                    onValueChange = { seeking = true; position = it },
                    onValueChangeFinished = { runCatching { player.seekTo(position.toInt()) }; seeking = false },
                    valueRange = 0f..duration,
                    enabled = prepared,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
