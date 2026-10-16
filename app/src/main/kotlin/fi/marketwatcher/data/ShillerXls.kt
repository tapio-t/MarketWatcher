package fi.marketwatcher.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads Robert Shiller's ie_data.xls (Excel 97 / BIFF8 inside an OLE2 compound file) without any
 * library: only the "Data" sheet, and only numeric cells (plain numbers, RK/MULRK and the cached
 * results of formulas). Returns the monthly rows that have a total return CAPE.
 *
 * Columns used: A = date as year.month (2026.09; 2026.1 = October), B = S&P price P,
 * O = total return CAPE ("TR CAPE").
 */
object ShillerXls {

    data class Month(val year: Int, val month: Int, val price: Double, val trCape: Double)

    class FormatException(msg: String) : Exception(msg)

    fun parse(bytes: ByteArray): List<Month> {
        val wb = workbookStream(bytes)
        val cells = dataSheetNumbers(wb)
        val rows = cells.keys.map { it ushr 16 }.toSortedSet()
        val out = ArrayList<Month>()
        for (r in rows) {
            val date = cells[key(r, 0)] ?: continue
            val price = cells[key(r, 1)] ?: continue
            val cape = cells[key(r, 14)] ?: continue
            if (date < 1800 || date > 2200) continue
            val y = date.toInt()
            val m = Math.round((date - y) * 100).toInt()
            if (m !in 1..12 || !cape.isFinite() || !price.isFinite() || cape <= 0 || price <= 0) continue
            out += Month(y, m, price, cape)
        }
        if (out.size < 12) throw FormatException("No total return CAPE rows found.")
        return out.sortedWith(compareBy({ it.year }, { it.month }))
    }

    private fun key(row: Int, col: Int) = (row shl 16) or col

    // ------------------------------------------------------------ OLE2 compound file

    private fun workbookStream(f: ByteArray): ByteArray {
        val b = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
        if (f.size < 512 || b.getLong(0) != 0xE11AB1A1E011CFD0uL.toLong()) throw FormatException("Not an .xls file.")
        val sectorSize = 1 shl b.getShort(30).toInt()
        val miniSize = 1 shl b.getShort(32).toInt()
        val numFat = b.getInt(44)
        val dirStart = b.getInt(48)
        val miniCutoff = b.getInt(56)
        val miniFatStart = b.getInt(60)
        var difat = b.getInt(68)
        fun off(s: Int) = 512 + s.toLong() * sectorSize
        fun sector(s: Int): ByteArray {
            val o = off(s); if (s < 0 || o + sectorSize > f.size) throw FormatException("Damaged file.")
            return f.copyOfRange(o.toInt(), (o + sectorSize).toInt())
        }

        // FAT sector list: 109 in the header, the rest in DIFAT sectors.
        val fatSectors = ArrayList<Int>()
        for (i in 0 until 109) { val s = b.getInt(76 + i * 4); if (s >= 0) fatSectors += s }
        var guard = 0
        while (difat >= 0 && fatSectors.size < numFat && guard++ < 10_000) {
            val d = ByteBuffer.wrap(sector(difat)).order(ByteOrder.LITTLE_ENDIAN)
            val per = sectorSize / 4 - 1
            for (i in 0 until per) { val s = d.getInt(i * 4); if (s >= 0) fatSectors += s }
            difat = d.getInt(per * 4)
        }
        val fat = IntArray(fatSectors.size * sectorSize / 4)
        fatSectors.forEachIndexed { k, s ->
            val bb = ByteBuffer.wrap(sector(s)).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until sectorSize / 4) fat[k * sectorSize / 4 + i] = bb.getInt(i * 4)
        }
        fun chain(start: Int, table: IntArray): List<Int> {
            val out = ArrayList<Int>(); var s = start; var n = 0
            while (s >= 0 && n++ < table.size + 1) { out += s; s = if (s < table.size) table[s] else -2 }
            return out
        }
        fun read(start: Int): ByteArray {
            val parts = chain(start, fat)
            val o = java.io.ByteArrayOutputStream(parts.size * sectorSize)
            parts.forEach { o.write(sector(it)) }
            return o.toByteArray()
        }

        // Directory: find "Workbook" (or old "Book") and the root entry (mini stream).
        val dir = ByteBuffer.wrap(read(dirStart)).order(ByteOrder.LITTLE_ENDIAN)
        var rootStart = -2; var wbStart = -2; var wbSize = 0L
        for (e in 0 until dir.capacity() / 128) {
            val base = e * 128
            val nameLen = dir.getShort(base + 64).toInt()
            if (nameLen <= 2) continue
            val name = String(CharArray(nameLen / 2 - 1) { dir.getChar(base + it * 2) })
            val type = dir.get(base + 66).toInt()
            val start = dir.getInt(base + 116)
            val size = dir.getInt(base + 120).toLong() and 0xFFFFFFFFL
            if (type == 5) rootStart = start
            if (type == 2 && (name == "Workbook" || name == "Book")) { wbStart = start; wbSize = size }
        }
        if (wbStart == -2) throw FormatException("No workbook in the file.")
        if (wbSize >= miniCutoff) return read(wbStart).copyOf(wbSize.toInt())

        // Small stream: stored in the mini stream.
        val mini = read(rootStart)
        val miniFatBytes = ByteBuffer.wrap(read(miniFatStart)).order(ByteOrder.LITTLE_ENDIAN)
        val miniFat = IntArray(miniFatBytes.capacity() / 4) { miniFatBytes.getInt(it * 4) }
        val o = java.io.ByteArrayOutputStream()
        chain(wbStart, miniFat).forEach { o.write(mini, it * miniSize, miniSize) }
        return o.toByteArray().copyOf(wbSize.toInt())
    }

    // ------------------------------------------------------------ BIFF8 records

    private fun dataSheetNumbers(wb: ByteArray): Map<Int, Double> {
        val b = ByteBuffer.wrap(wb).order(ByteOrder.LITTLE_ENDIAN)
        // Workbook globals: BOUNDSHEET (0x0085) gives each sheet's name and stream offset.
        var pos = 0
        var dataSheetOffset = -1
        val sheetOffsets = ArrayList<Int>()
        while (pos + 4 <= wb.size) {
            val type = b.getShort(pos).toInt() and 0xFFFF
            val len = b.getShort(pos + 2).toInt() and 0xFFFF
            val body = pos + 4
            if (type == 0x0085) {
                val offset = b.getInt(body)
                val cch = wb[body + 6].toInt() and 0xFF
                val flags = wb[body + 7].toInt()
                val name = if (flags and 1 == 0) String(wb, body + 8, cch, Charsets.ISO_8859_1)
                           else String(CharArray(cch) { b.getChar(body + 8 + it * 2) })
                sheetOffsets += offset
                if (name.trim().equals("Data", ignoreCase = true)) dataSheetOffset = offset
            }
            if (type == 0x000A) break   // EOF of globals
            pos = body + len
        }
        if (dataSheetOffset < 0) dataSheetOffset = sheetOffsets.lastOrNull() ?: throw FormatException("No sheets.")

        val cells = HashMap<Int, Double>()
        pos = dataSheetOffset
        while (pos + 4 <= wb.size) {
            val type = b.getShort(pos).toInt() and 0xFFFF
            val len = b.getShort(pos + 2).toInt() and 0xFFFF
            val body = pos + 4
            if (body + len > wb.size) break
            when (type) {
                0x0203 -> cells[key(u16(b, body), u16(b, body + 2))] = b.getDouble(body + 6)                 // NUMBER
                0x027E -> cells[key(u16(b, body), u16(b, body + 2))] = rk(b.getInt(body + 6))                // RK
                0x00BD -> {                                                                                  // MULRK
                    val row = u16(b, body); val first = u16(b, body + 2)
                    val n = (len - 6) / 6
                    for (i in 0 until n) cells[key(row, first + i)] = rk(b.getInt(body + 4 + i * 6 + 2))
                }
                0x0006 -> {                                                                                  // FORMULA
                    val numeric = !(wb[body + 12] == 0xFF.toByte() && wb[body + 13] == 0xFF.toByte())
                    if (numeric) cells[key(u16(b, body), u16(b, body + 2))] = b.getDouble(body + 6)
                }
                0x000A -> break                                                                              // EOF
            }
            pos = body + len
        }
        return cells
    }

    private fun u16(b: ByteBuffer, at: Int) = b.getShort(at).toInt() and 0xFFFF

    private fun rk(v: Int): Double {
        val d = if (v and 2 != 0) (v shr 2).toDouble()
                else java.lang.Double.longBitsToDouble((v.toLong() and 0xFFFFFFFCL) shl 32)
        return if (v and 1 != 0) d / 100 else d
    }
}
