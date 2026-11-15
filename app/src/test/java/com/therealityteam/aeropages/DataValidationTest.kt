package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.editor.ValidationForm
import com.therealityteam.aeropages.model.DataValidation
import com.therealityteam.aeropages.model.TextId
import com.therealityteam.aeropages.model.Texts
import com.therealityteam.aeropages.model.ValidationAlertStyle
import com.therealityteam.aeropages.model.ValidationOperator
import com.therealityteam.aeropages.model.ValidationType
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Excel's Data Validation: entries checked as they are made, the three alert styles, every kind of rule, lists, circles. */
class DataValidationTest {

    private fun editor() = SheetEditor().apply { load(Workbook.blank()) }

    /** [rule] on [r1],[c1]..[r2],[c2] through the dialog's path. */
    private fun SheetEditor.rule(r1: Int, c1: Int, r2: Int, c2: Int, rule: DataValidation) {
        select(r1, c1); extendSelection(r2 - r1, c2 - c1); setValidation(rule)
    }

    /** Types [text] into ([r], [c]) a key at a time, as the formula bar does, then leaves the cell. */
    private fun SheetEditor.typeKeys(r: Int, c: Int, text: String) {
        select(r, c)
        for (i in 1..text.length) typeCell(r, c, text.substring(0, i))
        select(r + 1, c)
    }

    private fun list(vararg items: String, style: ValidationAlertStyle = ValidationAlertStyle.STOP) =
        DataValidation(0, 0, 0, 0, ValidationType.LIST, items = items.toList(), errorStyle = style)

    @Test
    fun aListValueCanBeTypedAKeyAtATime() {
        val e = editor()
        e.rule(0, 0, 9, 0, list("Yes", "No"))
        e.typeKeys(0, 0, "Yes")   // "Y" and "Ye" are no list value; only the entry as made is checked
        assertEquals("Yes", e.sheet.cell(0, 0)!!.text)
        assertNull(e.validationAlert)
        e.typeKeys(1, 0, "no")
        assertEquals("a list matches regardless of case, as Excel's does", "no", e.sheet.cell(1, 0)!!.text)
        assertNull(e.validationAlert)
    }

    @Test
    fun stopPutsTheCellBackAndRetryGoesBackIntoIt() {
        val e = editor()
        e.rule(0, 0, 9, 0, DataValidation(0, 0, 0, 0, ValidationType.INTEGER, 1.0, 10.0))
        e.typeKeys(0, 0, "5")
        val depth = e.undoDepth
        e.typeKeys(0, 0, "50")
        val alert = e.validationAlert!!
        assertEquals(ValidationAlertStyle.STOP, alert.style)
        assertEquals(Texts.get(TextId.VALIDATION_OP_BETWEEN, Texts.get(TextId.VALIDATION_KIND_INTEGER), "1", "10"), alert.message)
        assertEquals("the cell is back as it was", "5", e.sheet.cell(0, 0)!!.text)
        assertEquals("the refused entry leaves no undo step", depth, e.undoDepth)
        assertEquals("the selection moved on", 1, e.selRow)
        e.retryEntry()
        assertNull(e.validationAlert)
        assertEquals(0, e.selRow)
        assertEquals("the entry is back to be put right", "50", e.editText(0, 0))
        e.typeCell(0, 0, "7"); e.select(1, 0)
        assertNull(e.validationAlert)
        assertEquals("7", e.sheet.cell(0, 0)!!.text)
        e.undo()
        assertEquals("one undo step for the corrected entry", "5", e.sheet.cell(0, 0)!!.text)
    }

    @Test
    fun warningAndInformationKeepTheEntryUnlessCancelled() {
        val e = editor()
        e.rule(0, 0, 9, 0, list("a", "b", style = ValidationAlertStyle.WARNING))
        e.typeKeys(0, 0, "zzz")
        assertEquals("zzz", e.sheet.cell(0, 0)!!.text)
        assertEquals(ValidationAlertStyle.WARNING, e.validationAlert!!.style)
        e.keepEntry()
        assertEquals("Yes keeps it", "zzz", e.sheet.cell(0, 0)!!.text)
        e.typeKeys(1, 0, "qqq")
        e.cancelEntry()
        assertNull("Cancel takes it back", e.sheet.cell(1, 0))
        e.rule(0, 1, 9, 1, DataValidation(0, 0, 0, 0, ValidationType.DECIMAL, 0.0, 1.0, errorStyle = ValidationAlertStyle.INFORMATION,
            errorTitle = "Rate", errorMessage = "Rates run from 0 to 1."))
        e.typeKeys(0, 1, "2")
        assertEquals("Rates run from 0 to 1.", e.validationAlert!!.message)
        e.keepEntry()
        assertEquals("2", e.sheet.cell(0, 1)!!.text)
        e.rule(0, 2, 9, 2, list("x", style = ValidationAlertStyle.WARNING))
        e.typeKeys(0, 2, "y")
        e.retryEntry()
        assertEquals("No goes back into the cell", 0, e.selRow)
        assertEquals("y", e.editText(0, 2))
    }

    @Test
    fun operatorsAndKinds() {
        val e = editor()
        e.rule(0, 0, 0, 0, DataValidation(0, 0, 0, 0, ValidationType.INTEGER, 10.0, operator = ValidationOperator.GREATER))
        e.setCell(0, 0, "10")
        assertEquals(Texts.get(TextId.VALIDATION_OP_GREATER, Texts.get(TextId.VALIDATION_KIND_INTEGER), "10"), e.validationError)
        e.setCell(0, 0, "11"); assertEquals("11", e.sheet.cell(0, 0)!!.text)
        e.setCell(0, 0, "11.5"); assertEquals("a whole number is required", Texts.get(TextId.VALIDATION_INTEGER_REQUIRED), e.validationError)
        e.rule(1, 0, 1, 0, DataValidation(0, 0, 0, 0, ValidationType.DECIMAL, 1.0, 2.0, operator = ValidationOperator.NOT_BETWEEN))
        e.setCell(1, 0, "1.5"); assertNull(e.sheet.cell(1, 0))
        e.setCell(1, 0, "2.5"); assertEquals("2.5", e.sheet.cell(1, 0)!!.text)
        // Dates and times are compared as the values they are typed as.
        e.rule(2, 0, 2, 0, ValidationForm.ruleOf(ValidationForm.Fields(ValidationType.DATE, minimum = "2026-01-01", maximum = "2026-12-31")).first!!)
        e.typeKeys(2, 0, "2027-03-01")
        assertTrue(e.validationAlert!!.message.contains("2026-01-01"))
        e.cancelEntry()
        e.typeKeys(2, 0, "2026-06-15")
        assertNull(e.validationAlert)
        e.rule(3, 0, 3, 0, ValidationForm.ruleOf(ValidationForm.Fields(ValidationType.TIME, minimum = "9:00", maximum = "17:30")).first!!)
        e.typeKeys(3, 0, "8:30")
        assertTrue(e.validationAlert!!.message.contains("17:30"))
        e.cancelEntry()
        e.typeKeys(3, 0, "12:00")
        assertNull(e.validationAlert)
        e.rule(4, 0, 4, 0, DataValidation(0, 0, 0, 0, ValidationType.TEXT_LENGTH, 5.0, operator = ValidationOperator.LESS_OR_EQUAL))
        e.setCell(4, 0, "hello"); assertEquals("hello", e.sheet.cell(4, 0)!!.text)
        e.setCell(4, 0, "hello!"); assertEquals("hello", e.sheet.cell(4, 0)!!.text)
        // A bound from a cell: the rule moves with it.
        e.setCell(0, 5, "100")
        e.rule(5, 0, 5, 0, DataValidation(0, 0, 0, 0, ValidationType.DECIMAL, operator = ValidationOperator.LESS_OR_EQUAL, formula1 = "\$F\$1"))
        e.setCell(5, 0, "150"); assertNull(e.sheet.cell(5, 0))
        e.setCell(0, 5, "200")
        e.setCell(5, 0, "150"); assertEquals("150", e.sheet.cell(5, 0)!!.text)
    }

    @Test
    fun aCustomRuleSeesTheEntryInItsCell() {
        val e = editor()
        // No repeats in A1:A10: the formula is written for A1 and moves down with each cell.
        e.rule(0, 0, 9, 0, DataValidation(0, 0, 0, 0, ValidationType.CUSTOM, formula1 = "COUNTIF(\$A\$1:\$A\$10,A1)=1"))
        e.typeKeys(0, 0, "apple")
        assertNull(e.validationAlert)
        e.typeKeys(3, 0, "apple")
        assertEquals(Texts.get(TextId.VALIDATION_CUSTOM), e.validationAlert!!.message)
        assertNull("put back", e.sheet.cell(3, 0))
        e.cancelEntry()
        e.setCell(4, 0, "apple")
        assertNull("the whole entry is judged in place too", e.sheet.cell(4, 0))
        e.setCell(4, 0, "pear")
        assertEquals("pear", e.sheet.cell(4, 0)!!.text)
    }

    @Test
    fun listsFromARangeANameAndIndirect() {
        val e = editor()
        listOf("Small", "Medium", "", "Large").forEachIndexed { r, t -> if (t.isNotEmpty()) e.setCell(r, 4, t) }
        e.rule(0, 0, 9, 0, DataValidation(0, 0, 0, 0, ValidationType.LIST, formula1 = "\$E\$1:\$E\$4"))
        e.select(0, 0)
        assertEquals("the source's values, blanks left out", listOf("Small", "Medium", "Large"), e.listChoices())
        e.pickFromList("Medium")
        assertEquals("Medium", e.sheet.cell(0, 0)!!.text)
        e.undo()
        assertNull("a pick is one undo step", e.sheet.cell(0, 0))
        e.typeKeys(1, 0, "Huge")
        assertNotNull(e.validationAlert)
        e.cancelEntry()
        // Dependent lists: B's list is the name A holds.
        listOf("Apple", "Pear").forEachIndexed { r, t -> e.setCell(r, 6, t) }
        listOf("Kale", "Leek", "Okra").forEachIndexed { r, t -> e.setCell(r, 7, t) }
        e.select(0, 6); e.extendSelection(1, 0); assertTrue(e.defineName("Fruit"))
        e.select(0, 7); e.extendSelection(2, 0); assertTrue(e.defineName("Veg"))
        e.rule(0, 1, 9, 1, DataValidation(0, 0, 0, 0, ValidationType.LIST, formula1 = "INDIRECT(C1)"))
        e.setCell(0, 2, "Fruit"); e.setCell(1, 2, "Veg")
        assertEquals(listOf("Apple", "Pear"), e.listChoices(0, 1))
        assertEquals("the formula moves down with the cell", listOf("Kale", "Leek", "Okra"), e.listChoices(1, 1))
        e.rule(0, 3, 0, 3, DataValidation(0, 0, 0, 0, ValidationType.LIST, formula1 = "Veg"))
        assertEquals(listOf("Kale", "Leek", "Okra"), e.listChoices(0, 3))
        e.rule(1, 3, 1, 3, list("a", "b").copy(dropdown = false))
        assertNull("no in-cell dropdown", e.listChoices(1, 3))
        assertFalse(e.hasListDropdown(1, 3))
        // A source with nothing in it lets anything in, as Excel's does.
        e.rule(2, 3, 2, 3, DataValidation(0, 0, 0, 0, ValidationType.LIST, formula1 = "\$Z\$1:\$Z\$9"))
        e.setCell(2, 3, "free")
        assertEquals("free", e.sheet.cell(2, 3)!!.text)
    }

    @Test
    fun rulesBelongToEachCell() {
        val e = editor()
        e.rule(0, 0, 9, 0, DataValidation(0, 0, 0, 0, ValidationType.CUSTOM, formula1 = "LEN(A1)<4"))
        // A new rule on A4:A5 takes those cells only; the old one keeps A1:A3 and A6:A10.
        e.rule(3, 0, 4, 0, list("x"))
        val rules = e.sheet.validations.sortedBy { it.r1 }
        assertEquals(listOf(0 to 2, 3 to 4, 5 to 9), rules.map { it.r1 to it.r2 })
        assertEquals("the lower piece's formula is written for its own top cell", "LEN(A6)<4", rules[2].formula1)
        e.setCell(7, 0, "long!")
        assertNull("still judged as the cell's own length", e.sheet.cell(7, 0))
        e.select(1, 0); e.clearValidations()
        assertEquals(listOf(0 to 0, 2 to 2, 3 to 4, 5 to 9), e.sheet.validations.sortedBy { it.r1 }.map { it.r1 to it.r2 })
        e.undo()
        assertEquals(3, e.sheet.validations.size)
    }

    @Test
    fun rowsInsertedMoveTheRulesAndTheirSources() {
        val e = editor()
        listOf("a", "b").forEachIndexed { r, t -> e.setCell(r + 5, 3, t) }   // D6:D7
        e.rule(0, 0, 2, 0, DataValidation(0, 0, 0, 0, ValidationType.LIST, formula1 = "\$D\$6:\$D\$7"))
        e.select(0, 0); e.insertRowAt(0)
        val dv = e.sheet.validations.single()
        assertEquals(1, dv.r1); assertEquals(3, dv.r2)
        assertEquals("\$D\$7:\$D\$8", dv.formula1)
        assertEquals(listOf("a", "b"), e.listChoices(1, 0))
        e.undo()
        assertEquals("\$D\$6:\$D\$7", e.sheet.validations.single().formula1)
    }

    @Test
    fun circleInvalidData() {
        val e = editor()
        listOf("3", "12", "7", "40", "").forEachIndexed { r, t -> if (t.isNotEmpty()) e.setCell(r, 0, t) }
        e.setCell(5, 0, "=A4*2")   // 80, worked out by a formula
        e.rule(0, 0, 9, 0, DataValidation(0, 0, 0, 0, ValidationType.INTEGER, 0.0, 10.0))
        assertEquals(3, e.circleInvalidData())
        assertTrue(e.isCircled(1, 0)); assertTrue(e.isCircled(3, 0)); assertTrue(e.isCircled(5, 0))
        assertFalse(e.isCircled(0, 0))
        e.setCell(1, 0, "9")
        assertFalse("put right, its circle goes", e.isCircled(1, 0))
        e.setCell(3, 0, "4")
        assertFalse("the formula's value is right now too", e.isCircled(5, 0))
        assertTrue(e.hasCircles.not())
        e.setCell(6, 0, "=100")
        assertEquals(1, e.circleInvalidData())
        e.clearValidationCircles()
        assertFalse(e.isCircled(6, 0))
    }

    @Test
    fun inputMessageAndNoAlert() {
        val e = editor()
        e.rule(0, 0, 0, 0, list("a").copy(inputTitle = "Code", inputMessage = "One letter", showError = false))
        e.select(0, 0)
        assertEquals("Code" to "One letter", e.inputMessage)
        e.typeKeys(0, 0, "anything")
        assertNull("no alert: any entry stays", e.validationAlert)
        assertEquals("anything", e.sheet.cell(0, 0)!!.text)
        e.select(3, 3)
        assertNull(e.inputMessage)
    }

    @Test
    fun aProtectedSheetKeepsItsRules() {
        val e = editor()
        e.toggleProtection()
        e.select(0, 0); e.setValidation(list("a"))
        assertTrue(e.sheet.validations.isEmpty())
    }

    @Test
    fun theDialogsFields() {
        val (rule, _) = ValidationForm.ruleOf(ValidationForm.Fields(ValidationType.DECIMAL, ValidationOperator.BETWEEN, "1,000", "=\$B\$1"))
        assertEquals(1000.0, rule!!.min, 0.0)
        assertNull(rule.formula1)
        assertEquals("\$B\$1", rule.formula2)
        val back = ValidationForm.fieldsOf(rule)
        assertEquals("1000", back.minimum)
        assertEquals("=\$B\$1", back.maximum)
        assertEquals(ValidationForm.Problem.MIN_OVER_MAX, ValidationForm.ruleOf(ValidationForm.Fields(ValidationType.INTEGER, minimum = "9", maximum = "1")).second)
        assertEquals(ValidationForm.Problem.BOUND, ValidationForm.ruleOf(ValidationForm.Fields(ValidationType.INTEGER, minimum = "ten", maximum = "1")).second)
        assertEquals(ValidationForm.Problem.LIST, ValidationForm.ruleOf(ValidationForm.Fields(ValidationType.LIST, source = " , ")).second)
        assertEquals(ValidationForm.Problem.FORMULA, ValidationForm.ruleOf(ValidationForm.Fields(ValidationType.CUSTOM, formula = "=")).second)
        val long = (1..60).joinToString(",") { "Item$it" }
        assertEquals("Excel's 255-character limit on a typed list", ValidationForm.Problem.LIST_TOO_LONG,
            ValidationForm.ruleOf(ValidationForm.Fields(ValidationType.LIST, source = long)).second)
        val list = ValidationForm.ruleOf(ValidationForm.Fields(ValidationType.LIST, source = "红，绿, 蓝")).first!!
        assertEquals("the Chinese comma separates too", listOf("红", "绿", "蓝"), list.items)
        assertEquals("=Sizes", ValidationForm.fieldsOf(ValidationForm.ruleOf(ValidationForm.Fields(ValidationType.LIST, source = "=Sizes")).first).source)
        val date = ValidationForm.ruleOf(ValidationForm.Fields(ValidationType.DATE, ValidationOperator.GREATER_OR_EQUAL, "2026-01-31")).first!!
        assertEquals("2026-01-31", ValidationForm.fieldsOf(date).minimum)
        val time = ValidationForm.ruleOf(ValidationForm.Fields(ValidationType.TIME, ValidationOperator.LESS, "9:30")).first!!
        assertEquals(9.5 / 24, time.min, 1e-9)
        assertEquals("9:30", ValidationForm.fieldsOf(time).minimum)
    }
}
