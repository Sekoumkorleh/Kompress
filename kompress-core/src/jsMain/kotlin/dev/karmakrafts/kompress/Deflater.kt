/*
 * Copyright (C) 2025 Karma Krafts & associates
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.karmakrafts.kompress

import dev.karmakrafts.kompress.fflate.Deflate
import dev.karmakrafts.kompress.fflate.DeflateOptions
import dev.karmakrafts.kompress.fflate.Zlib
import dev.karmakrafts.kompress.fflate.ZlibOptions
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set
import kotlin.math.min

private class DeflaterImpl( // @formatter:off
    private val raw: Boolean,
    initialLevel: Int
) : Deflater { // @formatter:on
    private var impl: FlateStreamWrapper = createImpl(initialLevel)

    private var finalRequested: Boolean = false
    private var finalSeen: Boolean = false
    private var inputPending: Boolean = false

    private val outQueue: ArrayDeque<ByteArray> = ArrayDeque()
    private var outOffset: Int = 0

    init {
        impl.ondata = ::onData
    }

    override var level: Int = initialLevel
        set(value) {
            field = value
            // Replace the underlying implementation and reattach handler
            impl.ondata = null
            impl = createImpl(value)
            impl.ondata = ::onData
            // Keep current state (queued output, pending input) intact
        }

    override var input: ByteArray = ByteArray(0)
        set(value) {
            inputPending = true
            field = value
        }

    override val needsInput: Boolean
        get() = !inputPending
    override val finished: Boolean
        get() = finalSeen && outQueue.isEmpty()

    override fun deflate(output: ByteArray): Int {
        // Push pending input to the underlying deflater
        if (inputPending && !finalSeen) {
            val dataToPush: Uint8Array = if (input.isNotEmpty()) {
                val arr = Uint8Array(input.size)
                for (i in input.indices) arr[i] = (input[i].toInt() and 0xFF).toByte()
                arr
            }
            else Uint8Array(0)
            impl.push(dataToPush, finalRequested)
            inputPending = false
        }

        // If finalization requested but no input pending, still need to flush by pushing empty final chunk
        if (!inputPending && finalRequested && !finalSeen) {
            impl.push(Uint8Array(0), true)
        }

        if (outQueue.isEmpty()) return 0

        var written = 0
        var remaining = output.size
        while (remaining > 0 && outQueue.isNotEmpty()) {
            val head = outQueue.first()
            val available = head.size - outOffset
            val toCopy = min(available, remaining)
            if (toCopy > 0) {
                head.copyInto(
                    destination = output,
                    destinationOffset = written,
                    startIndex = outOffset,
                    endIndex = outOffset + toCopy
                )
                written += toCopy
                remaining -= toCopy
                outOffset += toCopy
            }
            if (outOffset >= head.size) {
                outQueue.removeFirst()
                outOffset = 0
            }
        }
        return written
    }

    override fun finish() {
        finalRequested = true
    }

    override fun close() {
        impl.ondata = null
        outQueue.clear()
        outOffset = 0
        inputPending = false
        finalRequested = true
        finalSeen = true
    }

    private fun createImpl(level: Int): FlateStreamWrapper = FlateStreamWrapper(
        if (raw) Deflate(DeflateOptions(level, 6), null)
        else Zlib(ZlibOptions(level, 6))
    )

    private fun onData(data: Uint8Array, isFinal: Boolean) {
        if (data.length > 0) {
            val chunk = ByteArray(data.length)
            for (i in 0 until data.length) {
                chunk[i] = (data[i].toInt() and 0xFF).toByte()
            }
            outQueue.addLast(chunk)
        }
        if (isFinal) finalSeen = true
    }
}

actual fun Deflater(raw: Boolean, level: Int): Deflater = DeflaterImpl(raw, level)