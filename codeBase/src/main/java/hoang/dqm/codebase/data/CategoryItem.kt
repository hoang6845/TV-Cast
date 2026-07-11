package hoang.dqm.codebase.data

data class CategoryItem(
    var type: String = "",
    var value: String = "",
)

data class CategoryItemUrl(
    var type: String = "",
    var value: String = "",
    var url: String
)

data class CategoryItemChannel(
    var type: String = "",
    var value: String = "",
    var num: Int = 0
)