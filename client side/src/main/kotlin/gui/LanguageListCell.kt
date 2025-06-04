package gui

import javafx.scene.control.ListCell
import java.util.Locale

class LanguageListCell : ListCell<Locale>() {
    override fun updateItem(item: Locale?, empty: Boolean) {
        super.updateItem(item, empty)
        if (empty || item == null) {
            text = null
        } else {
            val langName = item.getDisplayLanguage(item)
            val countryName = item.getDisplayCountry(item)
            text = if (countryName.isNotEmpty()) "$langName ($countryName)" else langName
        }
    }
}