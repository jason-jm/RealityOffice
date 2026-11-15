package com.therealityteam.aeropages.format

import com.therealityteam.aeropages.model.Passwords
import com.therealityteam.aeropages.model.ProtectionHash
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.SheetProtection
import com.therealityteam.aeropages.model.Workbook

/**
 * Sheet and workbook protection as xlsx and ODS write it: Excel's `sheetProtection` (each permission as "is it
 * protected": `formatCells="0"` lets cells be formatted, `selectLockedCells="1"` keeps locked cells from being
 * selected) and `workbookProtection`, LibreOffice's `table:protected` / `table:protection-key` with
 * `loext:table-protection` and `table:structure-protected`. A password goes only where its hash can be read — Excel's
 * salted SHA-512 (or old 16-bit hash) in xlsx, an unsalted digest of the UTF-8 password in ODS.
 */
object ProtectionXml {
    // --- xlsx ---------------------------------------------------------------------------------

    /** `<sheetProtection …/>` for a protected [sheet], else "". */
    fun xlsxSheet(sheet: Sheet): String {
        if (!sheet.protected) return ""
        val p = sheet.protection
        fun flag(name: String, protectedWhen: Boolean, default: Boolean) =
            if (protectedWhen == default) "" else """ $name="${if (protectedWhen) 1 else 0}""""
        return buildString {
            append("<sheetProtection")
            append(xlsxPassword(p.passwords, "password", "algorithmName", "hashValue", "saltValue", "spinCount"))
            append(""" sheet="1"""")
            append(flag("objects", !p.editObjects, false)); append(flag("scenarios", !p.editScenarios, false))
            append(flag("formatCells", !p.formatCells, true)); append(flag("formatColumns", !p.formatColumns, true))
            append(flag("formatRows", !p.formatRows, true)); append(flag("insertColumns", !p.insertColumns, true))
            append(flag("insertRows", !p.insertRows, true)); append(flag("insertHyperlinks", !p.insertHyperlinks, true))
            append(flag("deleteColumns", !p.deleteColumns, true)); append(flag("deleteRows", !p.deleteRows, true))
            append(flag("selectLockedCells", !p.selectLocked, false)); append(flag("sort", !p.sort, true))
            append(flag("autoFilter", !p.autoFilter, true)); append(flag("pivotTables", !p.pivotTables, true))
            append(flag("selectUnlockedCells", !p.selectUnlocked, false))
            append("/>")
        }
    }

    /** A `sheetProtection` element's attributes ([attr] by local name) as protection; null when it does not protect the sheet. */
    fun readXlsxSheet(attr: (String) -> String?): SheetProtection? {
        fun on(name: String, default: Boolean) = attr(name)?.let { it == "1" || it.equals("true", true) } ?: default
        if (!on("sheet", false)) return null
        return SheetProtection(
            selectLocked = !on("selectLockedCells", false), selectUnlocked = !on("selectUnlockedCells", false),
            formatCells = !on("formatCells", true), formatColumns = !on("formatColumns", true), formatRows = !on("formatRows", true),
            insertColumns = !on("insertColumns", true), insertRows = !on("insertRows", true), insertHyperlinks = !on("insertHyperlinks", true),
            deleteColumns = !on("deleteColumns", true), deleteRows = !on("deleteRows", true), sort = !on("sort", true),
            autoFilter = !on("autoFilter", true), pivotTables = !on("pivotTables", true),
            editObjects = !on("objects", false), editScenarios = !on("scenarios", false),
            passwords = readXlsxPassword(attr, "password", "algorithmName", "hashValue", "saltValue", "spinCount"),
        )
    }

    /** `<workbookProtection lockStructure="1" …/>` for a workbook whose structure is locked, else "". */
    fun xlsxWorkbook(wb: Workbook): String {
        if (!wb.structureLocked) return ""
        return "<workbookProtection" + xlsxPassword(wb.structurePasswords, "workbookPassword", "workbookAlgorithmName",
            "workbookHashValue", "workbookSaltValue", "workbookSpinCount") + """ lockStructure="1"/>"""
    }

    /** A `workbookProtection` element read into [wb]. */
    fun readXlsxWorkbook(attr: (String) -> String?, wb: Workbook) {
        wb.structureLocked = attr("lockStructure")?.let { it == "1" || it.equals("true", true) } ?: false
        if (wb.structureLocked) wb.structurePasswords = readXlsxPassword(attr, "workbookPassword", "workbookAlgorithmName",
            "workbookHashValue", "workbookSaltValue", "workbookSpinCount")
    }

    private fun xlsxPassword(hashes: List<ProtectionHash>, legacy: String, algorithm: String, hash: String, salt: String, spin: String): String {
        val h = hashes.firstOrNull { it.forXlsx } ?: return ""
        return if (h.algorithm == ProtectionHash.LEGACY) """ $legacy="${h.value}""""
        else """ $algorithm="${h.algorithm}" $hash="${h.value}" $salt="${h.salt}" $spin="${h.spinCount}""""
    }

    private fun readXlsxPassword(attr: (String) -> String?, legacy: String, algorithm: String, hash: String, salt: String, spin: String): List<ProtectionHash> {
        val out = ArrayList<ProtectionHash>()
        val value = attr(hash); val saltValue = attr(salt)
        if (value != null && saltValue != null) out += ProtectionHash(attr(algorithm) ?: ProtectionHash.SHA512, value, saltValue, attr(spin)?.toIntOrNull() ?: 0)
        attr(legacy)?.takeIf { it.isNotBlank() && it != "0000" }?.let { out += ProtectionHash(ProtectionHash.LEGACY, it.uppercase()) }
        return out
    }

    // --- ODS ----------------------------------------------------------------------------------

    /** The `table:table` attributes for a protected [sheet]: `table:protected` and its key. */
    fun odsTableAttrs(sheet: Sheet): String {
        if (!sheet.protected) return ""
        return """ table:protected="true"""" + odsKey(sheet.protection.passwords)
    }

    /** LibreOffice's `loext:table-protection` (what may still be done) — the first thing in a protected table. */
    fun odsTableChild(sheet: Sheet): String {
        if (!sheet.protected) return ""
        val p = sheet.protection
        return """<loext:table-protection loext:select-protected-cells="${p.selectLocked}" loext:select-unprotected-cells="${p.selectUnlocked}"""" +
            (if (p.insertColumns) """ loext:insert-columns="true"""" else "") + (if (p.insertRows) """ loext:insert-rows="true"""" else "") +
            (if (p.deleteColumns) """ loext:delete-columns="true"""" else "") + (if (p.deleteRows) """ loext:delete-rows="true"""" else "") + "/>"
    }

    /** A `table:table` node's protection; null when it is not protected. */
    fun readOdsTable(attr: (String) -> String?, child: ((String) -> String?)?): SheetProtection? {
        if (attr("protected") != "true") return null
        fun c(name: String, default: Boolean) = child?.invoke(name)?.let { it == "true" } ?: default
        return SheetProtection(
            selectLocked = c("select-protected-cells", true), selectUnlocked = c("select-unprotected-cells", true),
            insertColumns = c("insert-columns", false), insertRows = c("insert-rows", false),
            deleteColumns = c("delete-columns", false), deleteRows = c("delete-rows", false),
            passwords = readOdsKey(attr),
        )
    }

    /** The `office:spreadsheet` attributes for a workbook whose structure is locked. */
    fun odsSpreadsheetAttrs(wb: Workbook): String =
        if (!wb.structureLocked) "" else """ table:structure-protected="true"""" + odsKey(wb.structurePasswords)

    /** `office:spreadsheet`'s structure protection read into [wb]. */
    fun readOdsSpreadsheet(attr: (String) -> String?, wb: Workbook) {
        wb.structureLocked = attr("structure-protected") == "true"
        if (wb.structureLocked) wb.structurePasswords = readOdsKey(attr)
    }

    private fun odsKey(hashes: List<ProtectionHash>): String {
        val h = hashes.firstOrNull { it.forOdf } ?: return ""
        return """ table:protection-key="${h.value}" table:protection-key-digest-algorithm="${Passwords.odfUri(h.algorithm)}""""
    }

    private fun readOdsKey(attr: (String) -> String?): List<ProtectionHash> {
        val key = attr("protection-key")?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(ProtectionHash(Passwords.fromOdfUri(attr("protection-key-digest-algorithm")), key))
    }
}
