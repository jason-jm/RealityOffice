package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.Passwords
import com.therealityteam.aeropages.model.ProtectionHash
import com.therealityteam.aeropages.model.SheetProtection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Excel's Protect Sheet (password, what everyone may still do) and Protect Workbook, in the editor and in xlsx and ODS. */
class SheetProtectionTest {
    private fun editor(vararg cells: Pair<String, String>): SheetEditor {
        val e = SheetEditor()
        for ((ref, text) in cells) e.sheet.ensure(ref.drop(1).toInt() - 1, ref[0] - 'A').text = text
        return e
    }

    @Test
    fun passwordsAreHashedAsExcelAndLibreOfficeDo() {
        // Excel's old 16-bit verifier, as printed in countless files.
        assertEquals(0x83AF, Passwords.xorVerifier("password"))
        assertEquals(0xCBEB, Passwords.xorVerifier("test"))
        assertTrue(Passwords.matches(listOf(ProtectionHash(ProtectionHash.LEGACY, "83AF")), "password"))
        assertFalse(Passwords.matches(listOf(ProtectionHash(ProtectionHash.LEGACY, "83AF")), "Password"))
        // A new password is kept as Excel's salted SHA-512 and ODF's SHA-256; both check, a wrong one does not.
        val hashes = Passwords.create("s3cret")
        assertEquals(listOf(ProtectionHash.SHA512, ProtectionHash.SHA256), hashes.map { it.algorithm })
        assertEquals(Passwords.SPIN, hashes[0].spinCount)
        assertTrue(Passwords.matches(listOf(hashes[0]), "s3cret")); assertTrue(Passwords.matches(listOf(hashes[1]), "s3cret"))
        assertFalse(Passwords.matches(hashes, "s3cre"))
        // ODF: the SHA-256 of the UTF-8 password (what LibreOffice writes), and the SHA-1 of older files.
        assertEquals("n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg=", Passwords.odf("test", ProtectionHash.SHA256))
        assertTrue(Passwords.matches(listOf(ProtectionHash(ProtectionHash.SHA1, Passwords.odf("abc", ProtectionHash.SHA1))), "abc"))
        assertTrue("no password: anything unprotects", Passwords.matches(emptyList(), "whatever"))
    }

    @Test
    fun whatProtectSheetAllowsIsAllowed() {
        val e = editor("A1" to "Region", "A2" to "North", "B2" to "5")
        e.protectSheet(SheetProtection(formatCells = true, insertRows = true, sort = true, selectUnlocked = true))
        val notices = e.protectionNotice
        e.select(1, 0); e.toggleBold()
        assertTrue("Format cells allowed: even a locked cell", e.sheet.cell(1, 0)!!.format.bold)
        e.insertRowAt(1)
        assertEquals("Insert rows allowed", "North", e.sheet.cell(2, 0)?.text)
        e.deleteRowAt(1)
        assertEquals("Delete rows not", notices + 1, e.protectionNotice)
        e.insertColAt(0)
        assertEquals("Insert columns not", notices + 2, e.protectionNotice)
        e.select(1, 1); e.setColWidthChars(20f)
        assertEquals("Format columns not", notices + 3, e.protectionNotice)
        // Select locked cells off: a locked cell cannot be selected, an unlocked one can.
        e.sheet.ensure(3, 3).unlocked = true
        e.protectSheet(SheetProtection(selectLocked = false))
        e.select(0, 0)
        assertEquals("stays where it was", 1 to 1, e.selRow to e.selCol)
        e.select(3, 3)
        assertEquals(3 to 3, e.selRow to e.selCol)
    }

    @Test
    fun aPasswordGuardsUnprotecting() {
        val e = editor("A1" to "1")
        e.protectSheet(SheetProtection(), "open sesame")
        assertTrue(e.sheetPasswordSet)
        assertFalse(e.unprotectSheet("wrong")); assertTrue(e.isProtected)
        assertTrue(e.unprotectSheet("open sesame")); assertFalse(e.isProtected)
        assertTrue("the password goes with the protection", e.sheet.protection.passwords.isEmpty())
        e.undo()
        assertTrue("undo protects it again, password and all", e.isProtected && e.sheetPasswordSet)
        assertFalse(e.unprotectSheet(""))
    }

    @Test
    fun protectWorkbookLocksTheStructure() {
        val e = editor("A1" to "1")
        e.protectWorkbook("pw")
        val sheets = e.workbook.sheets.size
        e.addSheet()
        assertEquals(sheets, e.workbook.sheets.size)
        assertFalse(e.renameSheet(0, "Data"))
        assertEquals(SheetEditor.SheetRefusal.STRUCTURE_LOCKED, e.sheetRefusal)
        assertFalse(e.hideSheet(0)); assertFalse(e.deleteSheet(0)); assertFalse(e.duplicateSheet(0))
        assertTrue("cells are still editable", run { e.typeCell(1, 1, "2"); e.sheet.cell(1, 1)?.text == "2" })
        assertFalse(e.unprotectWorkbook("PW"))
        assertTrue(e.unprotectWorkbook("pw"))
        e.addSheet()
        assertEquals(sheets + 1, e.workbook.sheets.size)
    }

    @Test
    fun xlsxKeepsPermissionsPasswordsAndTheStructureLock() {
        val e = editor("A1" to "1")
        e.protectSheet(SheetProtection(formatCells = true, sort = true, autoFilter = true, selectLocked = false), "pw")
        e.protectWorkbook("book")
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(e.workbook, it) }.toByteArray()
        val parts = OfficeXml.unzip(bytes)
        val sheetXml = String(parts["xl/worksheets/sheet1.xml"]!!)
        assertTrue(sheetXml.contains("""algorithmName="SHA-512""""))
        assertTrue(sheetXml.contains("""formatCells="0"""") && sheetXml.contains("""sort="0"""") && sheetXml.contains("""selectLockedCells="1""""))
        assertFalse("not allowed: left at its default (protected)", sheetXml.contains("insertRows"))
        val bookXml = String(parts["xl/workbook.xml"]!!)
        assertTrue(bookXml.contains("""lockStructure="1"""") && bookXml.contains("workbookHashValue"))
        assertTrue("before bookViews and sheets", bookXml.indexOf("workbookProtection") < bookXml.indexOf("<sheets>"))
        val back = XlsxReader.read(bytes)
        val p = back.sheets[0].protection
        assertTrue(back.sheets[0].protected)
        assertTrue(p.formatCells && p.sort && p.autoFilter && !p.selectLocked && p.selectUnlocked && !p.insertRows && !p.editObjects)
        assertTrue(Passwords.matches(p.passwords, "pw")); assertFalse(Passwords.matches(p.passwords, "PW"))
        assertTrue(back.structureLocked && Passwords.matches(back.structurePasswords, "book"))
        // Excel's old 16-bit hash is read too.
        val old = SheetEditor(); old.load(back)
        assertNull(com.therealityteam.aeropages.format.ProtectionXml.readXlsxSheet { mapOf("sheet" to "0")[it] })
        val legacy = com.therealityteam.aeropages.format.ProtectionXml.readXlsxSheet { mapOf("sheet" to "1", "password" to "83AF", "objects" to "1")[it] }!!
        assertTrue(Passwords.matches(legacy.passwords, "password"))
    }

    @Test
    fun odsKeepsTheProtectionAsLibreOfficeWritesIt() {
        val e = editor("A1" to "1")
        e.protectSheet(SheetProtection(insertRows = true, deleteColumns = true, selectUnlocked = false), "pw")
        e.protectWorkbook("book")
        val bytes = ByteArrayOutputStream().also { OdsWriter.write(e.workbook, it) }.toByteArray()
        val content = String(OfficeXml.unzip(bytes)["content.xml"]!!)
        assertTrue(content.contains("""table:protected="true""""))
        assertTrue(content.contains("""table:protection-key-digest-algorithm="http://www.w3.org/2000/09/xmldsig#sha256""""))
        assertTrue(content.contains("""loext:select-unprotected-cells="false"""") && content.contains("""loext:insert-rows="true""""))
        assertTrue(content.contains("""table:structure-protected="true""""))
        val back = OdsReader.read(bytes)
        val p = back.sheets[0].protection
        assertTrue(back.sheets[0].protected && p.insertRows && p.deleteColumns && !p.selectUnlocked && p.selectLocked && !p.formatCells)
        assertTrue(Passwords.matches(p.passwords, "pw"))
        assertTrue(back.structureLocked && Passwords.matches(back.structurePasswords, "book"))
    }

    /** With `-Daero.sampleDir`: a protected workbook (password "pw", book "book") for LibreOffice and the app. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        val e = editor("A1" to "Item", "B1" to "Qty", "A2" to "Pens", "B2" to "4", "A3" to "Ink", "B3" to "7")
        e.sheet.ensure(1, 1).unlocked = true; e.sheet.ensure(2, 1).unlocked = true
        e.protectSheet(SheetProtection(formatCells = true, sort = true, insertRows = true), "pw")
        e.protectWorkbook("book")
        File(dir, "protected.xlsx").outputStream().use { XlsxWriter.write(e.workbook, it) }
        File(dir, "protected.ods").outputStream().use { OdsWriter.write(e.workbook, it) }
    }
}
