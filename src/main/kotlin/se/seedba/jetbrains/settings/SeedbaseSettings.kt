package se.seedba.jetbrains.settings

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.options.Configurable
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

object SeedbaseSettings {
    private const val PREVIEW_ROWS_KEY = "se.seedba.previewRows"
    private const val DEFAULT_PREVIEW_ROWS = 20

    fun previewRows(): Int = PropertiesComponent.getInstance().getInt(PREVIEW_ROWS_KEY, DEFAULT_PREVIEW_ROWS)

    fun setPreviewRows(value: Int) {
        PropertiesComponent.getInstance().setValue(PREVIEW_ROWS_KEY, value, DEFAULT_PREVIEW_ROWS)
    }
}

class SeedbaseConfigurable : Configurable {
    private val field = JTextField(6)

    override fun getDisplayName(): String = "SeedBase"

    override fun createComponent(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT))
        panel.add(JLabel("Preview rows:"))
        field.text = SeedbaseSettings.previewRows().toString()
        panel.add(field)
        return panel
    }

    override fun isModified(): Boolean = field.text.trim() != SeedbaseSettings.previewRows().toString()

    override fun apply() {
        val value = field.text.trim().toIntOrNull()?.coerceIn(1, 5000) ?: SeedbaseSettings.previewRows()
        SeedbaseSettings.setPreviewRows(value)
        field.text = value.toString()
    }

    override fun reset() {
        field.text = SeedbaseSettings.previewRows().toString()
    }
}
