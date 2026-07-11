package hoang.dqm.codebase.data

import androidx.annotation.Keep

@Keep
sealed class ItemList<T> {
    data class DataItem<T>(val item: T) : ItemList<T>()
    object Placeholder : ItemList<Nothing>()
}