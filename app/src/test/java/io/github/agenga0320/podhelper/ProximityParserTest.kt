package io.github.agenga0320.podhelper

import io.github.agenga0320.podhelper.ble.LidState
import io.github.agenga0320.podhelper.ble.PodSelector
import io.github.agenga0320.podhelper.ble.ProximityParser
import io.github.agenga0320.podhelper.ble.Sighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 完整的 27 位元組向量取自 CAPod 的測試（GPL-3.0）：
 *  app/src/test/java/eu/darken/capod/pods/core/apple/ble/devices/airpods/AirPodsGen2Test.kt
 *  app/src/test/java/eu/darken/capod/pods/core/apple/ble/devices/DualApplePodsTest.kt
 * frame() 產生的是依同樣格式組出的假資料。
 */
class ProximityParserTest {

    private fun hex(s: String) = s.split(' ').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
    private fun parse(s: String) = ProximityParser.parse(hex(s)) ?: throw AssertionError("parse returned null: $s")

    /** 組一個 Proximity Pairing 訊息，後面補 0 到 27 位元組。 */
    private fun frame(status: Int, pods: Int = 0x99, flagsCase: Int = 0x05, lid: Int = 0x31, model: Int = 0x0F20, conn: Int = 0x05) =
        ByteArray(27).also {
            it[0] = 0x07; it[1] = 25; it[2] = 0x01
            it[3] = (model shr 8).toByte(); it[4] = model.toByte()
            it[5] = status.toByte(); it[6] = pods.toByte(); it[7] = flagsCase.toByte(); it[8] = lid.toByte()
            it[10] = conn.toByte()
        }

    @Test
    fun `AirPods 2 neighbor broadcast`() {
        val s = parse("07 19 01 0F 20 02 F9 8F 01 00 05 F2 7E 14 E0 54 0A 53 69 5B 7D F2 15 1F D7 B1 12")
        assertEquals(0x0F20, s.modelId)
        assertEquals("AirPods 2", s.modelName)
        assertNull(s.leftBattery)
        assertEquals(90, s.rightBattery)
        assertNull(s.caseBattery)
        assertFalse(s.leftCharging); assertFalse(s.rightCharging); assertFalse(s.caseCharging)
        assertFalse(s.leftInEar); assertTrue(s.rightInEar)
        assertFalse(s.primaryLeft)
        assertEquals(LidState.NOT_IN_CASE, s.lid)
        assertTrue(s.isConnectedToSomething)
    }

    @Test
    fun `battery nibbles swap with primary pod`() {
        // 0x0B：右耳 primary（資料對調）
        parse("07 19 01 0E 20 0B 98 94 52 00 05 09 73 3C 3D F9 2C 3E B3 DD 76 02 DD 4E 16 FD FB").let {
            assertEquals(90, it.leftBattery); assertEquals(80, it.rightBattery); assertFalse(it.primaryLeft)
        }
        // 0x2B：左耳 primary
        parse("07 19 01 0E 20 2B 89 94 52 00 05 09 73 3C 3D F9 2C 3E B3 DD 76 02 DD 4E 16 FD FB").let {
            assertEquals(90, it.leftBattery); assertEquals(80, it.rightBattery); assertTrue(it.primaryLeft)
        }
    }

    @Test
    fun `pod charging flags`() {
        parse("07 19 01 0E 20 51 89 94 52 00 00 F4 89 82 6D 3E 27 7F 26 62 57 D0 E2 A6 49 E9 35").let {
            assertFalse(it.leftCharging); assertTrue(it.rightCharging)
        }
        parse("07 19 01 0E 20 71 98 94 52 00 05 A5 37 31 B2 BD 42 68 0C 64 FD 00 99 4A E5 3E F4").let {
            assertTrue(it.leftCharging); assertFalse(it.rightCharging)
        }
        parse("07 19 01 0E 20 55 88 B4 59 00 05 4B FC DF 68 28 A5 45 52 65 9C FE 51 86 3A B5 DB").let {
            assertTrue(it.leftCharging); assertTrue(it.rightCharging)
        }
        parse("07 19 01 0E 20 00 F8 8F 03 00 05 4C 0F A0 C4 05 24 DD EB AF 92 99 FD 54 B1 06 48").let {
            assertFalse(it.leftCharging); assertFalse(it.rightCharging)
        }
    }

    @Test
    fun `case battery and charging`() {
        parse("07 19 01 0E 20 75 99 B4 31 00 05 77 C8 BA 0C 4E 1F BE AD 70 C5 40 71 D2 E9 17 A2").let {
            assertFalse(it.caseCharging); assertEquals(40, it.caseBattery)
        }
        parse("07 19 01 0E 20 75 A9 F4 51 00 05 A0 37 92 35 49 79 CC DC 27 94 8E FB 72 12 94 52").let {
            assertTrue(it.caseCharging); assertEquals(40, it.caseBattery)
        }
    }

    @Test
    fun `battery nibble edge values`() {
        val s = ProximityParser.parse(frame(status = 0x20, pods = 0xB0, flagsCase = 0x0A))!!
        assertEquals(0, s.leftBattery)      // 低 4 位 0
        assertEquals(100, s.rightBattery)   // 0xB > 10 → 100
        assertEquals(100, s.caseBattery)    // 0xA = 10
    }

    @Test
    fun `lid state`() {
        assertEquals(LidState.OPEN, parse("07 19 01 0E 20 55 AA B4 31 00 00 A1 D0 BD 82 D3 52 86 CA FC 11 62 DC 42 C6 92 8E").lid)
        assertEquals(LidState.CLOSED, parse("07 19 01 0E 20 55 AA B4 39 00 00 08 A6 DB 99 E0 5E 14 85 E5 C2 0B 68 D7 FF C3 A1").lid)
        assertEquals(LidState.OPEN, parse("07 19 01 0E 20 71 A9 92 31 00 00 DB 48 7F 32 8C CE 80 6F D9 27 98 D6 76 45 9D 62").lid)
        assertEquals(LidState.CLOSED, parse("07 19 01 0E 20 71 A9 92 38 00 00 44 91 C4 8B 85 98 DD 55 4E 6A CA BC B5 CA 8D 37").lid)
        // 盒外那顆耳機送的盒蓋位元不可信（CAPod issue 598）
        assertEquals(LidState.UNKNOWN, ProximityParser.parse(frame(status = 0x13, lid = 0x11))!!.lid)
        assertEquals(LidState.OPEN, ProximityParser.parse(frame(status = 0x04, lid = 0x31))!!.lid)
        assertEquals(LidState.NOT_IN_CASE, ProximityParser.parse(frame(status = 0x20, lid = 0x5A))!!.lid)
    }

    @Test
    fun `in ear detection`() {
        assertTrue(ProximityParser.parse(frame(0x22))!!.leftInEar)
        assertFalse(ProximityParser.parse(frame(0x20))!!.leftInEar)
        assertTrue(ProximityParser.parse(frame(0x09))!!.leftInEar)
        assertTrue(ProximityParser.parse(frame(0x29))!!.rightInEar)
        assertFalse(ProximityParser.parse(frame(0x21))!!.rightInEar)
        assertTrue(ProximityParser.parse(frame(0x03))!!.rightInEar)
        assertFalse(ProximityParser.parse(frame(0x01))!!.rightInEar)
    }

    @Test
    fun `rejects garbage and non pairing messages`() {
        assertNull(ProximityParser.parse(ByteArray(0)))
        assertNull(ProximityParser.parse(hex("07 19 01 0F 20")))                          // 長度不足
        assertNull(ProximityParser.parse(frame(0x20).also { it[2] = 0x07 }))               // 前綴不是 0x01
        assertNull(ProximityParser.parse(frame(0x20, model = 0x0A20)))                     // AirPods Max
        assertNull(ProximityParser.parse(hex("10 05 01 02 03 04 05")))                    // 只有別種 Continuity 訊息
    }

    @Test
    fun `finds pairing message after another continuity message`() {
        val data = hex("10 02 AA BB") + frame(0x22)
        assertEquals(0x0F20, ProximityParser.parse(data)!!.modelId)
    }

    @Test
    fun `selector prefers strong and connected pods`() {
        val connected = ProximityParser.parse(frame(0x22, conn = 0x05))!!
        val idle = ProximityParser.parse(frame(0x22, conn = 0x00))!!
        val now = 100_000L
        val mine = Sighting("A", -75, connected, now)
        val stranger = Sighting("B", -60, idle, now)

        // 未連線：-75 不夠近，只剩 B
        assertEquals("B", PodSelector.pick(listOf(mine, stranger), now, classicConnected = false)?.address)
        // 已連線：優先選有連著裝置的 A，即使 B 訊號較強
        assertEquals("A", PodSelector.pick(listOf(mine, stranger), now, classicConnected = true)?.address)
        // 太舊的廣播不算
        assertNull(PodSelector.pick(listOf(stranger.copy(timeMs = now - 20_000)), now, classicConnected = false))
        // 太遠的不算
        assertNull(PodSelector.pick(listOf(Sighting("C", -95, connected, now)), now, classicConnected = true))
    }
}
