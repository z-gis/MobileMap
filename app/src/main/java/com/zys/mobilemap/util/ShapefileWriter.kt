package com.zys.mobilemap.util

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.Calendar

/**
 * 纯 Kotlin 写出 ESRI Shapefile（不依赖 native/GDAL）：目前仅支持折线（PolyLine，shapeType=3），
 * 供轨迹分享导出使用（一次一条轨迹，单点串单部件，属性表一行）。
 *
 * 按 [ESRI Shapefile Technical Description](https://www.esri.com/news/arcuser/0703/files/terrdisk.pdf)
 * 规范落盘一组同名不同扩展的文件，缺一不可，须整组一起分享/导入才有意义（故上层打包成 zip）：
 * - `.shp`：几何主文件，100 字节大端/小端混排文件头 + 逐条记录（记录号/内容长度大端，其余小端）；
 * - `.shx`：索引文件，独立文件头 + 每条记录 8 字节（.shp 内偏移/内容长度，均大端，单位：16 位字）；
 * - `.dbf`：属性表，dBASE III 格式（文件头 + 字段描述 + 记录 + 结束符 0x1A），字段一律用字符型（'C'），
 *   避免数值型宽度/小数位换算歧义，接收端(QGIS/ArcGIS)读取兼容；
 * - `.prj`：WGS84 地理坐标系 WKT（轨迹点本身即 WGS84 经纬度，无需投影变换）；
 * - `.cpg`：属性表字段文本编码声明（本类固定写 UTF-8）。
 *
 * 坐标轴序统一为 x=经度、y=纬度（Shapefile 规范惯例，与调用方传入的 [lonLatPoints] 顺序一致）。
 */
object ShapefileWriter {

    /** shapeType=3：Arc/PolyLine（本类固定写出该类型） */
    private const val SHAPE_TYPE_POLYLINE = 3

    /** 文件代号，规范固定值 9994 */
    private const val FILE_CODE = 9994

    /** 版本号，规范固定值 1000 */
    private const val VERSION = 1000

    /** 单个文件头长度（字节），.shp/.shx 相同 */
    private const val HEADER_BYTES = 100

    /** 属性表字段最大字符宽度（dBASE III 单字段上限 254） */
    private const val DBF_MAX_CHAR_WIDTH = 254

    private const val CHARSET_NAME = "UTF-8"

    /**
     * WGS84 地理坐标系标准 WKT（EPSG:4326），与 [GeoCalc] 量算口径一致，无需投影参数。
     * 采用主流 GIS 软件（QGIS/ArcGIS/PROJ）导出的同一写法，保证兼容性。
     */
    private const val PRJ_WGS84_WKT =
        "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137.0,298.257223563]]," +
                "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]"

    /**
     * 属性表一列：[name] 为字段名（自动截断为不超过 10 字节 ASCII，dBASE III 限制），
     * [width] 为字符型字段宽度（不超 [DBF_MAX_CHAR_WIDTH]），[value] 为字段值文本。
     */
    data class DbfField(val name: String, val width: Int, val value: String)

    /**
     * 写出一条轨迹对应的完整 shp 文件组到 [baseFile]（不含扩展名）下，返回生成的全部文件（含 .shp/.shx/.dbf/.prj/.cpg）。
     *
     * @param lonLatPoints 轨迹点序列，每点为 `[经度, 纬度]`（调用方需先把内部 `[纬度, 经度, 高程]` 转换轴序）
     * @param fields       属性表字段（对应唯一一条记录），为空时属性表仅写出记录删除标志位
     * @return 至少两点且写出成功时返回生成的文件列表；点数不足或写盘异常返回空列表（不抛出，由调用方判断提示）
     */
    fun writePolyline(baseFile: File, lonLatPoints: List<DoubleArray>, fields: List<DbfField>): List<File> {
        if (lonLatPoints.size < 2) return emptyList()
        val charset = Charset.forName(CHARSET_NAME)
        return try {
            val shpFile = File(baseFile.parentFile, baseFile.name + ".shp")
            val shxFile = File(baseFile.parentFile, baseFile.name + ".shx")
            val dbfFile = File(baseFile.parentFile, baseFile.name + ".dbf")
            val prjFile = File(baseFile.parentFile, baseFile.name + ".prj")
            val cpgFile = File(baseFile.parentFile, baseFile.name + ".cpg")

            baseFile.parentFile?.mkdirs()

            val box = boundingBox(lonLatPoints)
            val recordContentBytes = writeShp(shpFile, box, lonLatPoints)
            writeShx(shxFile, box, recordContentBytes)
            writeDbf(dbfFile, fields, charset)
            prjFile.writeText(PRJ_WGS84_WKT, charset)
            cpgFile.writeText(CHARSET_NAME, charset)

            listOf(shpFile, shxFile, dbfFile, prjFile, cpgFile)
        } catch (e: Exception) {
            runCatching {
                File(baseFile.parentFile, baseFile.name + ".shp").delete()
                File(baseFile.parentFile, baseFile.name + ".shx").delete()
                File(baseFile.parentFile, baseFile.name + ".dbf").delete()
                File(baseFile.parentFile, baseFile.name + ".prj").delete()
                File(baseFile.parentFile, baseFile.name + ".cpg").delete()
            }
            emptyList()
        }
    }

    /** 四至 [minX(minLon), minY(minLat), maxX(maxLon), maxY(maxLat)]，供 .shp/.shx 文件头与 .dbf 无关，仅几何文件用 */
    private fun boundingBox(points: List<DoubleArray>): DoubleArray {
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        for (p in points) {
            if (p[0] < minLon) minLon = p[0]
            if (p[0] > maxLon) maxLon = p[0]
            if (p[1] < minLat) minLat = p[1]
            if (p[1] > maxLat) maxLat = p[1]
        }
        return doubleArrayOf(minLon, minLat, maxLon, maxLat)
    }

    /**
     * 写出 .shp 文件：文件头 + 单条记录（一条轨迹视为一个部件）。
     * 返回该记录「内容段」字节数（不含 8 字节记录号/长度头），供 [.shx] 换算偏移与长度。
     */
    private fun writeShp(file: File, box: DoubleArray, points: List<DoubleArray>): Int {
        val numPoints = points.size
        // 内容 = shapeType(4) + box(64) + numParts(4) + numPoints(4) + parts(4*1) + points(16*numPoints)
        val contentBytes = 4 + 64 + 4 + 4 + 4 + 16 * numPoints
        val totalBytes = HEADER_BYTES + 8 + contentBytes

        ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFileHeader(box, totalBytes, SHAPE_TYPE_POLYLINE)

            // ── 记录头（大端）：记录号 1 起、内容长度（16 位字为单位）──
            position(HEADER_BYTES)
            order(ByteOrder.BIG_ENDIAN)
            putInt(1)
            putInt(contentBytes / 2)
            order(ByteOrder.LITTLE_ENDIAN)

            // ── 记录内容（小端）──
            putInt(SHAPE_TYPE_POLYLINE)
            putDouble(box[0]); putDouble(box[1]); putDouble(box[2]); putDouble(box[3])
            putDouble(0.0); putDouble(0.0); putDouble(0.0); putDouble(0.0) // Zmin/Zmax/Mmin/Mmax 未使用
            putInt(1)          // numParts：单条轨迹固定一个部件
            putInt(numPoints)  // numPoints
            putInt(0)          // parts[0]：本部件起始点索引
            for (p in points) {
                putDouble(p[0])
                putDouble(p[1])
            }
            flip()
        }.let { buffer ->
            RandomAccessFile(file, "rw").use { it.write(buffer.array(), 0, buffer.limit()) }
        }
        return contentBytes
    }

    /** 写出 .shx 文件：独立文件头 + 单条索引记录（偏移指向几何记录在 .shp 中的起始位置，含 8 字节记录头） */
    private fun writeShx(file: File, box: DoubleArray, recordContentBytes: Int) {
        val totalBytes = HEADER_BYTES + 8
        ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFileHeader(box, totalBytes, SHAPE_TYPE_POLYLINE)
            // 索引记录（大端）：偏移 = 文件头之后的第一条记录起始位置 = 100 字节 / 2
            position(HEADER_BYTES)
            order(ByteOrder.BIG_ENDIAN)
            putInt(HEADER_BYTES / 2)
            putInt(recordContentBytes / 2)
            flip()
        }.let { buffer ->
            RandomAccessFile(file, "rw").use { it.write(buffer.array(), 0, buffer.limit()) }
        }
    }

    /**
     * 写出 dBASE III 属性表：全部字段为字符型（'C'），固定一条记录（对应唯一一条轨迹）。
     * [fields] 为空时仍写出合法的空字段表（仅一条记录删除标志 + 结束符）。
     */
    private fun writeDbf(file: File, fields: List<DbfField>, charset: Charset) {
        val headerLen = 32 + fields.size * 32 + 1
        val recordLen = 1 + fields.sumOf { it.width.coerceIn(1, DBF_MAX_CHAR_WIDTH) }
        val totalBytes = headerLen + recordLen + 1 // +1 = 文件结束符 0x1A

        ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0x03.toByte()) // dBASE III 无备注文件
            putCalendar(Calendar.getInstance())
            putInt(1) // 记录数：本导出固定一条
            putShort(headerLen.toShort())
            putShort(recordLen.toShort())
            position(29) // 跳过 12..28 保留区与 27 MDX 标志（allocate 初始为 0）
            put(0x00)    // 数据库存储语言驱动 ID，留空

            var fieldHeaderPos = 32
            for (f in fields) {
                position(fieldHeaderPos)
                putFixedAscii(f.name, 11)
                put('C'.code.toByte())
                putInt(0) // 字段保留（位移）4 字节
                put(f.width.coerceIn(1, DBF_MAX_CHAR_WIDTH).toByte())
                put(0)    // 小数位数（字符型固定 0）
                putShort(0) // 保留
                fieldHeaderPos += 32
            }
            position(headerLen - 1)
            put(0x0D.toByte()) // 文件头结束标志

            position(headerLen)
            put(0x20.toByte()) // 记录删除标志：空格 = 未删除
            for (f in fields) {
                putFixedPaddedText(f.value, f.width.coerceIn(1, DBF_MAX_CHAR_WIDTH), charset)
            }
            put(0x1A.toByte()) // 文件结束符
            flip()
        }.let { buffer ->
            RandomAccessFile(file, "rw").use { it.write(buffer.array(), 0, buffer.limit()) }
        }
    }

    /** 写入 3 字节 YY/MM/DD（年份以 1900 为基准） */
    private fun ByteBuffer.putCalendar(cal: Calendar) {
        put((cal.get(Calendar.YEAR) - 1900).toByte())
        put((cal.get(Calendar.MONTH) + 1).toByte())
        put(cal.get(Calendar.DAY_OF_MONTH).toByte())
    }

    /** 写入 .shp/.shx 共用的 100 字节文件头（大端段：0..23、24..27；小端段：28..99） */
    private fun ByteBuffer.putFileHeader(box: DoubleArray, totalBytes: Int, shapeType: Int) {
        order(ByteOrder.BIG_ENDIAN)
        putInt(0, FILE_CODE)
        for (i in 1..5) putInt(i * 4, 0) // 5 个未使用 int
        putInt(24, totalBytes / 2)        // 文件长度（含头，单位：16 位字）
        order(ByteOrder.LITTLE_ENDIAN)
        putInt(28, VERSION)
        putInt(32, shapeType)
        putDouble(36, box[0]); putDouble(44, box[1]); putDouble(52, box[2]); putDouble(60, box[3])
        putDouble(68, 0.0); putDouble(76, 0.0); putDouble(84, 0.0); putDouble(92, 0.0)
    }

    /** 定长 ASCII 写入（不足补 \u0000，超长截断），用于字段名；从当前 position 顺序写入并前移 len 字节 */
    private fun ByteBuffer.putFixedAscii(text: String, len: Int) {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        val n = minOf(len, bytes.size)
        put(bytes, 0, n)
        repeat(len - n) { put(0) }
    }

    /** 定长文本写入（不足补空格，超长按字符截断至宽度内），用于字段值 */
    private fun ByteBuffer.putFixedPaddedText(text: String, len: Int, charset: Charset) {
        var s = text
        var bytes = s.toByteArray(charset)
        while (bytes.size > len) {
            s = s.substring(0, s.length - 1)
            bytes = s.toByteArray(charset)
        }
        put(bytes)
        repeat(len - bytes.size) { put(0x20.toByte()) }
    }
}
