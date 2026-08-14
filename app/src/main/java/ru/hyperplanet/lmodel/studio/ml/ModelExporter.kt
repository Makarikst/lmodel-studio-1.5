package ru.hyperplanet.lmodel.studio.ml

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Реальный экспорт корпуса в ONNX (protobuf ModelProto) и TFLite (size-prefixed FlatBuffer).
 * Граф: query[vocab] × knowledge[vocab×sentences] → scores[sentences] (retrieval / cosine-TF).
 */
object ModelExporter {

    data class ExportResult(
        val onnxFile: File?,
        val tfliteFile: File?,
        val vocabSize: Int,
        val numSentences: Int,
        val error: String? = null
    )

    fun export(trainedJson: String?, outDir: File, baseName: String): ExportResult {
        val data = InferenceEngine.deserializeTrained(trainedJson ?: "")
            ?: return ExportResult(null, null, 0, 0, "Модель не обучена или данные повреждены")
        if (data.sentences.isEmpty()) {
            return ExportResult(null, null, 0, 0, "Пустой корпус")
        }
        outDir.mkdirs()
        val vocab = data.vocabulary.sorted()
        val vocabSize = vocab.size.coerceAtLeast(1)
        val numSentences = data.sentences.size
        val wordToIdx = vocab.withIndex().associate { it.value to it.index }

        val matrix = Array(numSentences) { FloatArray(vocabSize) }
        for (si in data.sentences.indices) {
            val words = MarkovTrainer.tokenizeText(data.sentences[si])
            val counts = mutableMapOf<Int, Int>()
            for (w in words) {
                val idx = wordToIdx[w] ?: continue
                counts[idx] = (counts[idx] ?: 0) + 1
            }
            val norm = sqrt(counts.values.sumOf { it * it.toDouble() }).toFloat().coerceAtLeast(1e-6f)
            for ((idx, c) in counts) matrix[si][idx] = c / norm
        }

        return try {
            val onnx = File(outDir, "$baseName.onnx")
            writeOnnx(onnx, matrix, vocab)
            val tflite = File(outDir, "$baseName.tflite")
            writeTflite(tflite, matrix)
            ExportResult(onnx, tflite, vocabSize, numSentences)
        } catch (e: Exception) {
            ExportResult(null, null, vocabSize, numSentences, e.message ?: e.toString())
        }
    }

    // ===================== ONNX =====================

    private fun writeOnnx(file: File, matrix: Array<FloatArray>, vocab: List<String>) {
        val rows = matrix.size
        val cols = matrix[0].size
        // knowledge stored as [cols, rows] for MatMul(query[1,cols], knowledge[cols,rows]) -> [1,rows]
        val transposed = FloatArray(rows * cols)
        for (r in 0 until rows) for (c in 0 until cols) transposed[c * rows + r] = matrix[r][c]

        val graph = ByteArrayOutputStream()
        pbString(graph, 1, "lmodel_retrieval")

        // Node MatMul
        val node = ByteArrayOutputStream()
        pbString(node, 1, "query"); pbString(node, 1, "knowledge")
        pbString(node, 2, "scores"); pbString(node, 3, "matmul_scores"); pbString(node, 4, "MatMul")
        pbBytes(graph, 2, node.toByteArray())

        // Initializer knowledge
        pbBytes(graph, 5, tensorProto("knowledge", longArrayOf(cols.toLong(), rows.toLong()), transposed))

        // Inputs / outputs
        pbBytes(graph, 11, valueInfo("query", longArrayOf(1, cols.toLong())))
        pbBytes(graph, 12, valueInfo("scores", longArrayOf(1, rows.toLong())))
        pbString(graph, 10, "vocab=${vocab.size};sentences=$rows;sample=${vocab.take(16).joinToString("|")}")

        val model = ByteArrayOutputStream()
        pbVarint(model, 1, 8) // ir_version
        pbString(model, 2, "LModelStudio")
        pbString(model, 3, "1.25")
        pbBytes(model, 7, graph.toByteArray())
        val opset = ByteArrayOutputStream(); pbVarint(opset, 3, 13)
        pbBytes(model, 8, opset.toByteArray())

        FileOutputStream(file).use { it.write(model.toByteArray()) }
    }

    private fun tensorProto(name: String, dims: LongArray, data: FloatArray): ByteArray {
        val t = ByteArrayOutputStream()
        pbString(t, 1, name)
        for (d in dims) pbVarint(t, 2, d)
        pbVarint(t, 3, 1) // FLOAT
        val raw = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        data.forEach { raw.putFloat(it) }
        pbBytes(t, 9, raw.array())
        return t.toByteArray()
    }

    private fun valueInfo(name: String, dims: LongArray): ByteArray {
        val shape = ByteArrayOutputStream()
        for (d in dims) {
            val dim = ByteArrayOutputStream(); pbVarint(dim, 1, d)
            pbBytes(shape, 1, dim.toByteArray())
        }
        val tensorType = ByteArrayOutputStream()
        pbVarint(tensorType, 1, 1)
        pbBytes(tensorType, 2, shape.toByteArray())
        val type = ByteArrayOutputStream()
        pbBytes(type, 1, tensorType.toByteArray())
        val vi = ByteArrayOutputStream()
        pbString(vi, 1, name)
        pbBytes(vi, 2, type.toByteArray())
        return vi.toByteArray()
    }

    private fun pbVarint(out: ByteArrayOutputStream, field: Int, value: Long) {
        writeKey(out, field, 0); writeVarint(out, value)
    }
    private fun pbString(out: ByteArrayOutputStream, field: Int, s: String) {
        writeKey(out, field, 2); writeVarint(out, s.toByteArray().size.toLong()); out.write(s.toByteArray())
    }
    private fun pbBytes(out: ByteArrayOutputStream, field: Int, data: ByteArray) {
        writeKey(out, field, 2); writeVarint(out, data.size.toLong()); out.write(data)
    }
    private fun writeKey(out: ByteArrayOutputStream, field: Int, wire: Int) {
        writeVarint(out, (field.toLong() shl 3) or wire.toLong())
    }
    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7F.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7
        }
        out.write((v and 0x7F).toInt())
    }

    // ===================== TFLite =====================

    private fun writeTflite(file: File, matrix: Array<FloatArray>) {
        val rows = matrix.size
        val cols = matrix[0].size
        val weight = FloatArray(rows * cols)
        for (r in 0 until rows) System.arraycopy(matrix[r], 0, weight, r * cols, cols)
        val bias = FloatArray(rows)

        val bytes = TfliteModelWriter().write(
            weight = weight,
            bias = bias,
            inputLen = cols,
            outputLen = rows
        )
        FileOutputStream(file).use { it.write(bytes) }
    }

    /**
     * Пишет size-prefixed FlatBuffer Model (TFLite schema).
     * Subgraph: FullyConnected(query, weight, bias) → scores.
     */
    private class TfliteModelWriter {
        private var storage = ByteArray(64)
        private var end = 0 // write position from start growing up... we'll grow from end like FB
        // Use grow-from-end pattern
        private var space = 2048
        private var bb = ByteArray(space)
        private var spaceLeft = space

        private fun used() = space - spaceLeft

        private fun ensure(n: Int) {
            if (n <= spaceLeft) return
            var newSpace = space
            while (newSpace - used() < n) newSpace *= 2
            val neu = ByteArray(newSpace)
            val u = used()
            System.arraycopy(bb, space - u, neu, newSpace - u, u)
            spaceLeft += newSpace - space
            space = newSpace
            bb = neu
        }

        private fun pad(alignment: Int) {
            val align = used() % alignment
            if (align == 0) return
            val p = alignment - align
            ensure(p)
            for (i in 0 until p) { spaceLeft--; bb[spaceLeft] = 0 }
        }

        private fun putU8(v: Int) {
            ensure(1); spaceLeft--; bb[spaceLeft] = (v and 0xFF).toByte()
        }
        private fun putI16(v: Int) {
            pad(2); ensure(2)
            spaceLeft -= 2
            bb[spaceLeft] = (v and 0xFF).toByte()
            bb[spaceLeft + 1] = ((v shr 8) and 0xFF).toByte()
        }
        private fun putI32(v: Int) {
            pad(4); ensure(4)
            spaceLeft -= 4
            bb[spaceLeft] = (v and 0xFF).toByte()
            bb[spaceLeft + 1] = ((v shr 8) and 0xFF).toByte()
            bb[spaceLeft + 2] = ((v shr 16) and 0xFF).toByte()
            bb[spaceLeft + 3] = ((v shr 24) and 0xFF).toByte()
        }
        private fun putOff(off: Int) {
            putI32(used() - off + 4)
        }

        private fun createString(s: String): Int {
            val bytes = s.toByteArray(Charsets.UTF_8)
            pad(4)
            ensure(bytes.size + 1)
            spaceLeft--; bb[spaceLeft] = 0
            for (i in bytes.size - 1 downTo 0) { spaceLeft--; bb[spaceLeft] = bytes[i] }
            putI32(bytes.size)
            return used()
        }

        private fun createByteVector(data: ByteArray): Int {
            pad(4)
            ensure(data.size)
            for (i in data.size - 1 downTo 0) { spaceLeft--; bb[spaceLeft] = data[i] }
            putI32(data.size)
            return used()
        }

        private fun createIntVector(data: IntArray): Int {
            pad(4)
            for (i in data.size - 1 downTo 0) putI32(data[i])
            putI32(data.size)
            return used()
        }

        private fun createOffsetVector(offs: IntArray): Int {
            pad(4)
            for (i in offs.size - 1 downTo 0) putOff(offs[i])
            putI32(offs.size)
            return used()
        }

        /** Generic table: pairs of (vtable_slot, value) where value is already relative offset or scalar written. */
        private fun startTable(): MutableList<Int?> {
            return mutableListOf()
        }

        private fun endTable(slots: Array<Int?>): Int {
            // slots indexed by field id (0-based). Write object then vtable.
            pad(4)
            val objStart = used()
            // Write fields in reverse field-id order that have values — actually FB stores fields in order of increasing address
            // Simpler fixed approach for known tables:
            throw IllegalStateException("use typed helpers")
        }

        private fun table(fieldSlots: List<Pair<Int, () -> Unit>>): Int {
            // fieldSlots: vtable slot (4,6,8...) to writer that pushes value onto buffer
            pad(4)
            val written = HashMap<Int, Int>()
            for ((slot, writer) in fieldSlots.sortedByDescending { it.first }) {
                writer()
                written[slot] = used()
            }
            val objBottom = used()
            putI32(0) // soffset placeholder
            val vtRef = used()
            val objSize = vtRef - objBottom + 4

            val maxSlot = written.keys.maxOrNull() ?: 4
            val nFields = maxSlot / 2 - 1 // slot 4 → field 0
            val vtFieldCount = nFields + 1
            val offsets = IntArray(vtFieldCount)
            for ((slot, pos) in written) {
                val fid = slot / 2 - 2
                if (fid in offsets.indices) offsets[fid] = pos - objBottom
            }
            // write vtable
            for (i in vtFieldCount - 1 downTo 0) putI16(offsets[i])
            putI16(objSize)
            val vtSize = (vtFieldCount + 2) * 2
            putI16(vtSize)
            val vtStart = used()
            // patch soffset
            val soffset = vtStart - vtRef
            val abs = space - vtRef
            bb[abs] = (soffset and 0xFF).toByte()
            bb[abs + 1] = ((soffset shr 8) and 0xFF).toByte()
            bb[abs + 2] = ((soffset shr 16) and 0xFF).toByte()
            bb[abs + 3] = ((soffset shr 24) and 0xFF).toByte()
            return vtRef
        }

        fun write(weight: FloatArray, bias: FloatArray, inputLen: Int, outputLen: Int): ByteArray {
            fun fbytes(a: FloatArray): ByteArray {
                val b = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                a.forEach { b.putFloat(it) }
                return b.array()
            }
            val wBytes = fbytes(weight)
            val bBytes = fbytes(bias)

            val bufData0 = createByteVector(ByteArray(0))
            val bufData1 = createByteVector(wBytes)
            val bufData2 = createByteVector(bBytes)

            fun bufferTable(dataOff: Int) = table(listOf(
                4 to {
                    putOff(dataOff)
                }
            ))
            val buf0 = bufferTable(bufData0)
            val buf1 = bufferTable(bufData1)
            val buf2 = bufferTable(bufData2)
            val buffersVec = createOffsetVector(intArrayOf(buf0, buf1, buf2))

            val shapeIn = createIntVector(intArrayOf(1, inputLen))
            val shapeW = createIntVector(intArrayOf(outputLen, inputLen))
            val shapeB = createIntVector(intArrayOf(outputLen))
            val shapeOut = createIntVector(intArrayOf(1, outputLen))
            val nIn = createString("query")
            val nW = createString("weight")
            val nB = createString("bias")
            val nOut = createString("scores")

            fun tensorTable(shape: Int, type: Int, buffer: Int, name: Int) = table(listOf(
                4 to { putOff(shape) },
                6 to { putU8(type); pad(4) },
                8 to { putI32(buffer) },
                10 to { putOff(name) }
            ))
            // FLOAT32 = 0
            val t0 = tensorTable(shapeIn, 0, 0, nIn)
            val t1 = tensorTable(shapeW, 0, 1, nW)
            val t2 = tensorTable(shapeB, 0, 2, nB)
            val t3 = tensorTable(shapeOut, 0, 0, nOut)
            val tensorsVec = createOffsetVector(intArrayOf(t0, t1, t2, t3))

            // OperatorCode: FullyConnected = 9
            val opCode = table(listOf(
                4 to { putU8(9); pad(4) }
            ))
            val opCodesVec = createOffsetVector(intArrayOf(opCode))

            val opIn = createIntVector(intArrayOf(0, 1, 2))
            val opOut = createIntVector(intArrayOf(3))
            val op = table(listOf(
                4 to { putI32(0) },
                6 to { putOff(opIn) },
                8 to { putOff(opOut) }
            ))
            val opsVec = createOffsetVector(intArrayOf(op))

            val sgIn = createIntVector(intArrayOf(0))
            val sgOut = createIntVector(intArrayOf(3))
            val sgName = createString("main")
            val subgraph = table(listOf(
                4 to { putOff(tensorsVec) },
                6 to { putOff(sgIn) },
                8 to { putOff(sgOut) },
                10 to { putOff(opsVec) },
                12 to { putOff(sgName) }
            ))
            val subgraphsVec = createOffsetVector(intArrayOf(subgraph))
            val desc = createString("LModelStudio v1.25 retrieval")

            val model = table(listOf(
                4 to { putI32(3) }, // version
                6 to { putOff(opCodesVec) },
                8 to { putOff(subgraphsVec) },
                10 to { putOff(desc) },
                12 to { putOff(buffersVec) }
            ))

            putOff(model) // root
            val contentSize = used()
            val content = ByteArray(contentSize)
            System.arraycopy(bb, space - contentSize, content, 0, contentSize)
            val out = ByteBuffer.allocate(4 + contentSize).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(contentSize)
            out.put(content)
            return out.array()
        }
    }
}
